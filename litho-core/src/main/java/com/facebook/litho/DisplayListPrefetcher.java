/**
 * Copyright (c) 2017-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.litho;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import android.annotation.TargetApi;
import android.os.Build;
import android.view.Display;
import android.view.View;

/**
 * {@link Runnable} that is used to prefetch display lists of components for which layout has been
 * already calculated but not yet appeared on screen. This will allow for faster drawing time when
 * these components come to screen.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public final class DisplayListPrefetcher implements Runnable {

  /**
   * Keeps average display list creation time per unique component type defined by component class
   * name.
   */
  private static final HashMap<String, Long> sAverageDLPrefetchDurationNs = new HashMap<>();

  private static DisplayListPrefetcher sDisplayListPrefetcher = new DisplayListPrefetcher();

  private final Queue<WeakReference<LayoutState>> mLayoutStates;

  private long mFrameIntervalNs;
  private WeakReference<View> mHostingView;

  private DisplayListPrefetcher() {
    mLayoutStates = new LinkedList<>();
  }

  public static DisplayListPrefetcher getInstance() {
    return sDisplayListPrefetcher;
  }

  public synchronized void setHostingView(View view) {
    if (mHostingView == null || mHostingView.get() != view) {
      mHostingView = new WeakReference<>(view);
    }
    initIfNeeded(view);
  }

  private void initIfNeeded(View view) {
    if (mFrameIntervalNs > 0) {
      return;
    }

    final Display display = view.getDisplay();
    float refreshRate = 60.0f;
    if (!view.isInEditMode() && display != null) {
      final float displayRefreshRate = display.getRefreshRate();
      if (displayRefreshRate >= 30.0f) {
        refreshRate = displayRefreshRate;
      }
    }

    mFrameIntervalNs = (long) (1000000000 / refreshRate);
  }

  synchronized void addLayoutState(LayoutState layoutState) {
    mLayoutStates.add(new WeakReference<>(layoutState));
  }

  @Override
  public void run() {
    if (mFrameIntervalNs == 0) {
      // Not yet initialized.
      return;
    }

    final View hostingView = mHostingView.get();
    if (hostingView == null) {
      return;
    }

    ComponentsSystrace.beginSection("DisplayListPrefetcher");

    final long latestFrameVsyncNs = TimeUnit.MILLISECONDS.toNanos(hostingView.getDrawingTime());
    final long nextVsyncNs = latestFrameVsyncNs + mFrameIntervalNs;

    while (true) {
      final LayoutState currentLayoutState = getValidLayoutStateFromQueue();
      if (currentLayoutState == null) {
        break;
      }

      final LayoutOutput currentLayoutOutput =
          currentLayoutState.getNextLayoutOutputForDLPrefetch();
      final String currentComponentType = currentLayoutOutput.getComponent().getSimpleName();
      final long startPrefetchNs = System.nanoTime();

      if (!canPrefetchOnTime(currentComponentType, startPrefetchNs, nextVsyncNs)) {
        break;
      }

      currentLayoutState.createDisplayList(currentLayoutOutput);
      if (currentLayoutOutput.getDisplayList() != null) {
        // successfully created DL
        final long actualElapsedNs = System.nanoTime() - startPrefetchNs;
        updateAveragePrefetchDuration(currentComponentType, actualElapsedNs);
      }
    }

    ComponentsSystrace.endSection();
  }

  private boolean canPrefetchOnTime(String componentType, long startTimeNs, long deadlineNs) {
    final Long expectedPrefetchDurationNs = sAverageDLPrefetchDurationNs.get(componentType);
    return expectedPrefetchDurationNs == null
        || (startTimeNs + expectedPrefetchDurationNs < deadlineNs);
  }

  /**
   * @return the next {@link LayoutState} from the queue that has non-zero elements to process.
   */
  private LayoutState getValidLayoutStateFromQueue() {
    WeakReference<LayoutState> currentLayoutState = mLayoutStates.peek();
    while (currentLayoutState != null) {
      final LayoutState layoutState = currentLayoutState.get();
      if (layoutState == null || !layoutState.hasItemsForDLPrefetch()) {
        mLayoutStates.remove();
        currentLayoutState = mLayoutStates.peek();
      } else {
        break;
      }
    }

    if (currentLayoutState == null) {
      // Queue is empty.
      return null;
    }

    return currentLayoutState.get();
  }

  private void updateAveragePrefetchDuration(String componentType, long actualElapsedNs) {
    final Long expectedPrefetchDurationNs = sAverageDLPrefetchDurationNs.get(componentType);
    final long updatedValue;
    if (expectedPrefetchDurationNs == null) {
      updatedValue = actualElapsedNs;
    } else {
      // Not actual average, but good approximation.
      updatedValue = (expectedPrefetchDurationNs / 4 * 3) + (actualElapsedNs / 4);
    }
    sAverageDLPrefetchDurationNs.put(componentType, updatedValue);
  }

  public synchronized boolean hasPrefetchItems() {
    return !mLayoutStates.isEmpty();
  }
}
