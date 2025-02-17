/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.wallpaper.asset;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityManagerCompat;

import java.util.Map;
import java.util.Objects;

/**
 * Implementation of {@link Asset} that wraps another {@link Asset} but keeps an LRU cache of
 * bitmaps generated by {@link #decodeBitmap(int, int, BitmapReceiver)} to avoid having to decode
 * the same bitmap multiple times.
 * The cache key is the wrapped Asset and the target Width and Height requested, so that we only
 * reuse bitmaps of the same size.
 */
public class BitmapCachingAsset extends Asset {

    private static class CacheKey {
        final Asset mAsset;

        /** a (width x height) of (0 x 0) represents the full image */
        final int mWidth;
        final int mHeight;
        final boolean mRtl;
        final Rect mRect;

        CacheKey(Asset asset, int width, int height) {
            this(asset, width, height, false, null);
        }

        CacheKey(Asset asset, int width, int height, boolean rtl, Rect rect) {
            mAsset = asset;
            mWidth = width;
            mHeight = height;
            mRtl = rtl;
            mRect = rect;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mAsset, mWidth, mHeight);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof CacheKey
                    && (Objects.equals(this.mAsset, ((CacheKey) obj).mAsset))
                    && ((CacheKey) obj).mWidth == this.mWidth
                    && ((CacheKey) obj).mHeight == this.mHeight
                    && ((CacheKey) obj).mRtl == this.mRtl
                    && (Objects.equals(this.mRect, ((CacheKey) obj).mRect));
        }
    }

    private static int cacheSize = 100 * 1024 * 1024; // 100MiB
    private static LruCache<CacheKey, Bitmap> sCache = new LruCache<CacheKey, Bitmap>(cacheSize) {
        @Override protected int sizeOf(CacheKey key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private final boolean mIsLowRam;
    private final Asset mOriginalAsset;

    public BitmapCachingAsset(Context context, Asset originalAsset) {
        mOriginalAsset = originalAsset instanceof BitmapCachingAsset
                ? ((BitmapCachingAsset) originalAsset).mOriginalAsset : originalAsset;
        mIsLowRam = ActivityManagerCompat.isLowRamDevice(
                (ActivityManager) context.getApplicationContext().getSystemService(
                        Context.ACTIVITY_SERVICE));
    }

    @Override
    public void decodeBitmap(int targetWidth, int targetHeight, boolean useHardwareBitmapIfPossible,
            BitmapReceiver receiver) {
        // Skip the cache in low ram devices
        if (mIsLowRam) {
            mOriginalAsset.decodeBitmap(targetWidth, targetHeight, useHardwareBitmapIfPossible,
                    receiver);
            return;
        }
        CacheKey key = new CacheKey(mOriginalAsset, targetWidth, targetHeight);
        Bitmap cached = sCache.get(key);
        if (cached != null) {
            receiver.onBitmapDecoded(cached);
        } else {
            BitmapReceiver cachingReceiver = bitmap -> {
                if (bitmap != null) {
                    sCache.put(key, bitmap);
                }
                receiver.onBitmapDecoded(bitmap);
            };
            if (targetWidth == 0 && targetHeight == 0) {
                mOriginalAsset.decodeBitmap(cachingReceiver);
            } else {
                mOriginalAsset.decodeBitmap(targetWidth, targetHeight, useHardwareBitmapIfPossible,
                        cachingReceiver);
            }
        }
    }

    @Override
    public void decodeBitmap(BitmapReceiver receiver) {
        decodeBitmap(0, 0, receiver);
    }

    @Override
    public void decodeBitmapRegion(Rect rect, int targetWidth, int targetHeight,
            boolean shouldAdjustForRtl, BitmapReceiver receiver) {
        // Skip the cache in low ram devices
        if (mIsLowRam) {
            mOriginalAsset.decodeBitmapRegion(rect, targetWidth, targetHeight, shouldAdjustForRtl,
                    receiver);
            return;
        }
        CacheKey key = new CacheKey(mOriginalAsset, targetWidth, targetHeight, shouldAdjustForRtl,
                rect);
        Bitmap cached = sCache.get(key);
        if (cached != null) {
            receiver.onBitmapDecoded(cached);
        } else {
            mOriginalAsset.decodeBitmapRegion(rect, targetWidth, targetHeight, shouldAdjustForRtl,
                    bitmap -> {
                        if (bitmap != null) {
                            sCache.put(key, bitmap);
                        }
                        receiver.onBitmapDecoded(bitmap);
                    });
        }
    }

    @Override
    public void decodeRawDimensions(@Nullable Activity activity, DimensionsReceiver receiver) {
        mOriginalAsset.decodeRawDimensions(activity, receiver);
    }

    @Override
    public boolean supportsTiling() {
        return mOriginalAsset.supportsTiling();
    }

    @Override
    public void loadPreviewImage(Activity activity, ImageView imageView, int placeholderColor,
            boolean offsetToStart) {
        // Honor the original Asset's preview image loading
        mOriginalAsset.loadPreviewImage(activity, imageView, placeholderColor, offsetToStart);
    }

    @Override
    public void loadPreviewImage(Activity activity, ImageView imageView, int placeholderColor,
            boolean offsetToStart, @Nullable Map<Point, Rect> cropHints) {
        // Honor the original Asset's preview image loading
        mOriginalAsset.loadPreviewImage(activity, imageView, placeholderColor, offsetToStart,
                cropHints);
    }
}
