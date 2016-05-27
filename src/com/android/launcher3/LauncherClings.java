/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.accessibility.AccessibilityManager;

import com.android.launcher3.util.Thunk;

public class LauncherClings implements OnClickListener, OnKeyListener {
    private static final String WORKSPACE_CLING_DISMISSED_KEY = "cling_gel.workspace.dismissed";

    private static final String TAG_CROP_TOP_AND_SIDES = "crop_bg_top_and_sides";

    private static final int SHOW_CLING_DURATION = 250;
    private static final int DISMISS_CLING_DURATION = 200;

    @Thunk Launcher mLauncher;
    private LayoutInflater mInflater;
    @Thunk boolean mIsVisible;

    /** Ctor */
    public LauncherClings(Launcher launcher) {
        mLauncher = launcher;
        mInflater = LayoutInflater.from(mLauncher);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.cling_dismiss_longpress_info) {
            dismissLongPressCling();
        }
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.isPrintingKey()) {
            // Should ignore all printing keys, otherwise they come to the search box.
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // Menu key goes to the overview mode similar to longpress, therefore it needs to
            // dismiss the clings.
            dismissLongPressCling();
        }
        return false;
    }

    public void showLongPressCling(boolean showWelcome) {
        mIsVisible = true;
        ViewGroup root = (ViewGroup) mLauncher.findViewById(R.id.launcher);
        View cling = mInflater.inflate(R.layout.longpress_cling, root, false);

        cling.setOnLongClickListener(new OnLongClickListener() {

            @Override
            public boolean onLongClick(View v) {
                mLauncher.showOverviewMode(true);
                dismissLongPressCling();
                return true;
            }
        });

        final ViewGroup content = (ViewGroup) cling.findViewById(R.id.cling_content);
        mInflater.inflate(showWelcome ? R.layout.longpress_cling_welcome_content
                : R.layout.longpress_cling_content, content);
        final View button = content.findViewById(R.id.cling_dismiss_longpress_info);
        button.setOnClickListener(this);
        button.setOnKeyListener(this);

        if (TAG_CROP_TOP_AND_SIDES.equals(content.getTag())) {
            Drawable bg = new BorderCropDrawable(mLauncher.getResources().getDrawable(R.drawable.cling_bg),
                    true, true, true, false);
            content.setBackground(bg);
        }

        mLauncher.onLauncherClingShown();
        root.addView(cling);

        if (showWelcome) {
            // This is the first cling being shown. No need to animate.
            return;
        }

        // Animate
        content.getViewTreeObserver().addOnGlobalLayoutListener(new OnGlobalLayoutListener() {

            @Override
            public void onGlobalLayout() {
                content.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                ObjectAnimator anim;
                if (TAG_CROP_TOP_AND_SIDES.equals(content.getTag())) {
                    content.setTranslationY(-content.getMeasuredHeight());
                    anim = LauncherAnimUtils.ofFloat(content, View.TRANSLATION_Y, 0);
                } else {
                    content.setScaleX(0);
                    content.setScaleY(0);
                    PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1);
                    PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1);
                    anim = LauncherAnimUtils.ofPropertyValuesHolder(content, scaleX, scaleY);
                }

                anim.setDuration(SHOW_CLING_DURATION);
                anim.setInterpolator(new LogDecelerateInterpolator(100, 0));
                anim.start();
            }
        });
    }

    @Thunk void dismissLongPressCling() {
        Runnable dismissCb = new Runnable() {
            public void run() {
                final View cling = mLauncher.findViewById(R.id.longpress_cling);
                // To catch cases where siblings of top-level views are made invisible, just check whether
                // the cling is directly set to GONE before dismissing it.
                if (cling != null && cling.getVisibility() != View.GONE) {
                    final Runnable cleanUpClingCb = new Runnable() {
                        public void run() {
                            cling.setVisibility(View.GONE);
                            mLauncher.getSharedPrefs().edit()
                                    .putBoolean(WORKSPACE_CLING_DISMISSED_KEY, true)
                                    .apply();
                            mIsVisible = false;
                            mLauncher.onLauncherClingDismissed();
                        }
                    };
                    cling.animate().alpha(0).setDuration(DISMISS_CLING_DURATION)
                            .withEndAction(cleanUpClingCb);
                }
            }
        };
        mLauncher.getWorkspace().post(dismissCb);
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    /** Returns whether the clings are enabled or should be shown */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private boolean areClingsEnabled() {
        // disable clings when running in a test harness
        if(ActivityManager.isRunningInTestHarness()) return false;

        // Disable clings for accessibility when explore by touch is enabled
        final AccessibilityManager a11yManager = (AccessibilityManager) mLauncher.getSystemService(
                Launcher.ACCESSIBILITY_SERVICE);
        if (a11yManager.isTouchExplorationEnabled()) {
            return false;
        }

        // Restricted secondary users (child mode) will potentially have very few apps
        // seeded when they start up for the first time. Clings won't work well with that
        if (Utilities.ATLEAST_JB_MR2) {
            UserManager um = (UserManager) mLauncher.getSystemService(Context.USER_SERVICE);
            Bundle restrictions = um.getUserRestrictions();
            if (restrictions.getBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, false)) {
                return false;
            }
        }
        if (Settings.Secure.getInt(mLauncher.getContentResolver(),
                Settings.Secure.SKIP_FIRST_USE_HINTS, 0) == 1) {
            return false;
        }
        return true;
    }

    public boolean shouldShowFirstRunOrMigrationClings() {
        return areClingsEnabled() &&
            !mLauncher.getSharedPrefs().getBoolean(WORKSPACE_CLING_DISMISSED_KEY, false);
    }

    public static void markFirstRunClingDismissed(Context ctx) {
        Utilities.getPrefs(ctx).edit()
                .putBoolean(WORKSPACE_CLING_DISMISSED_KEY, true)
                .apply();
    }
}
