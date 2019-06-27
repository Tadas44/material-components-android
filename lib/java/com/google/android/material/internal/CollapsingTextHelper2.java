/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.google.android.material.internal;

import android.animation.TimeInterpolator;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.core.math.MathUtils;
import androidx.core.text.TextDirectionHeuristicsCompat;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import com.google.android.material.animation.AnimationUtils;
import com.google.android.material.resources.CancelableFontCallback;
import com.google.android.material.resources.CancelableFontCallback.ApplyFont;
import com.google.android.material.resources.TextAppearance;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

/**
 * Helper class for rendering and animating collapsed text.
 *
 * @hide
 */
@RestrictTo(LIBRARY_GROUP)
public final class CollapsingTextHelper2 {

  // Pre-JB-MR2 doesn't support HW accelerated canvas scaled text so we will workaround it
  // by using our own texture
  private static final boolean USE_SCALING_TEXTURE = Build.VERSION.SDK_INT < 18;

  private static final boolean DEBUG_DRAW = false;
  private static final Paint DEBUG_DRAW_PAINT;

  static {
    DEBUG_DRAW_PAINT = DEBUG_DRAW ? new Paint() : null;
    if (DEBUG_DRAW_PAINT != null) {
      DEBUG_DRAW_PAINT.setAntiAlias(true);
      DEBUG_DRAW_PAINT.setColor(Color.MAGENTA);
    }
  }

  private final View view;

  private boolean drawTitle;
  private float expandedFraction;

  private final Rect expandedBounds;
  private final Rect collapsedBounds;
  private final RectF currentBounds;
  private int expandedTextGravity = Gravity.CENTER_VERTICAL;
  private int collapsedTextGravity = Gravity.CENTER_VERTICAL;
  private float expandedTextSize = 15;
  private float collapsedTextSize = 15;
  private ColorStateList expandedTextColor;
  private ColorStateList collapsedTextColor;

  private float expandedDrawY;
  private float collapsedDrawY;
  private float expandedDrawX;
  private float collapsedDrawX;
  private float currentDrawX;
  private float currentDrawY;
  private Typeface collapsedTypeface;
  private Typeface expandedTypeface;
  private Typeface currentTypeface;
  private CancelableFontCallback expandedFontCallback;
  private CancelableFontCallback collapsedFontCallback;

  private CharSequence text;
  private CharSequence textToDraw;
  private boolean isRtl;

  private boolean useTexture;
  private Bitmap expandedTitleTexture;
  private Paint texturePaint;
  private float textureAscent;
  private float textureDescent;

  private float scale;
  private float currentTextSize;

  private int[] state;

  private boolean boundsChanged;

  private final TextPaint textPaint;
  private final TextPaint tmpPaint;

  private TimeInterpolator positionInterpolator;
  private TimeInterpolator textSizeInterpolator;

  private float collapsedShadowRadius;
  private float collapsedShadowDx;
  private float collapsedShadowDy;
  private ColorStateList collapsedShadowColor;

  private float expandedShadowRadius;
  private float expandedShadowDx;
  private float expandedShadowDy;
  private ColorStateList expandedShadowColor;

  // BEGIN MODIFICATION: Added fields
  private CharSequence mTextToDrawCollapsed;
  private Bitmap mCollapsedTitleTexture;
  private Bitmap mCrossSectionTitleTexture;
  private StaticLayout mTextLayout;
  private float mCollapsedTextBlend;
  private float mExpandedTextBlend;
  private float mExpandedFirstLineDrawX;
  private int maxLines = 2;
  private float lineSpacingExtra = 0;
  private float lineSpacingMultiplier = 1;
  // END MODIFICATION

  public CollapsingTextHelper2(View view) {
    this.view = view;

    textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    tmpPaint = new TextPaint(textPaint);

    collapsedBounds = new Rect();
    expandedBounds = new Rect();
    currentBounds = new RectF();
  }

  public void setTextSizeInterpolator(TimeInterpolator interpolator) {
    textSizeInterpolator = interpolator;
    recalculate();
  }

  public void setPositionInterpolator(TimeInterpolator interpolator) {
    positionInterpolator = interpolator;
    recalculate();
  }

  public void setExpandedTextSize(float textSize) {
    if (expandedTextSize != textSize) {
      expandedTextSize = textSize;
      recalculate();
    }
  }

  public void setCollapsedTextSize(float textSize) {
    if (collapsedTextSize != textSize) {
      collapsedTextSize = textSize;
      recalculate();
    }
  }

  public void setCollapsedTextColor(ColorStateList textColor) {
    if (collapsedTextColor != textColor) {
      collapsedTextColor = textColor;
      recalculate();
    }
  }

  public void setExpandedTextColor(ColorStateList textColor) {
    if (expandedTextColor != textColor) {
      expandedTextColor = textColor;
      recalculate();
    }
  }

  public void setExpandedBounds(int left, int top, int right, int bottom) {
    if (!rectEquals(expandedBounds, left, top, right, bottom)) {
      expandedBounds.set(left, top, right, bottom);
      boundsChanged = true;
      onBoundsChanged();
    }
  }

  public void setExpandedBounds(Rect bounds) {
    setExpandedBounds(bounds.left, bounds.top, bounds.right, bounds.bottom);
  }

  public void setCollapsedBounds(int left, int top, int right, int bottom) {
    if (!rectEquals(collapsedBounds, left, top, right, bottom)) {
      collapsedBounds.set(left, top, right, bottom);
      boundsChanged = true;
      onBoundsChanged();
    }
  }

  public void setCollapsedBounds(Rect bounds) {
    setCollapsedBounds(bounds.left, bounds.top, bounds.right, bounds.bottom);
  }

  public void getCollapsedTextActualBounds(RectF bounds) {
    boolean isRtl = calculateIsRtl(text);

    bounds.left =
        !isRtl ? collapsedBounds.left : collapsedBounds.right - calculateCollapsedTextWidth();
    bounds.top = collapsedBounds.top;
    bounds.right = !isRtl ? bounds.left + calculateCollapsedTextWidth() : collapsedBounds.right;
    bounds.bottom = collapsedBounds.top + getCollapsedTextHeight();
  }

  public float calculateCollapsedTextWidth() {
    if (text == null) {
      return 0;
    }
    getTextPaintCollapsed(tmpPaint);
    return tmpPaint.measureText(text, 0, text.length());
  }

  public float getExpandedTextHeight() {
    getTextPaintExpanded(tmpPaint);
    // Return expanded height measured from the baseline.
    return -tmpPaint.ascent();
  }

  public float getCollapsedTextHeight() {
    getTextPaintCollapsed(tmpPaint);
    // Return collapsed height measured from the baseline.
    return -tmpPaint.ascent();
  }

  private void getTextPaintExpanded(TextPaint textPaint) {
    textPaint.setTextSize(expandedTextSize);
    textPaint.setTypeface(expandedTypeface);
  }

  private void getTextPaintCollapsed(TextPaint textPaint) {
    textPaint.setTextSize(collapsedTextSize);
    textPaint.setTypeface(collapsedTypeface);
  }

  void onBoundsChanged() {
    drawTitle =
        collapsedBounds.width() > 0
            && collapsedBounds.height() > 0
            && expandedBounds.width() > 0
            && expandedBounds.height() > 0;
  }

  public void setExpandedTextGravity(int gravity) {
    if (expandedTextGravity != gravity) {
      expandedTextGravity = gravity;
      recalculate();
    }
  }

  public int getExpandedTextGravity() {
    return expandedTextGravity;
  }

  public void setCollapsedTextGravity(int gravity) {
    if (collapsedTextGravity != gravity) {
      collapsedTextGravity = gravity;
      recalculate();
    }
  }

  public int getCollapsedTextGravity() {
    return collapsedTextGravity;
  }

  public void setCollapsedTextAppearance(int resId) {
    TextAppearance textAppearance = new TextAppearance(view.getContext(), resId);

    if (textAppearance.textColor != null) {
      collapsedTextColor = textAppearance.textColor;
    }
    if (textAppearance.textSize != 0) {
      collapsedTextSize = textAppearance.textSize;
    }
    if (textAppearance.shadowColor != null) {
      collapsedShadowColor = textAppearance.shadowColor;
    }
    collapsedShadowDx = textAppearance.shadowDx;
    collapsedShadowDy = textAppearance.shadowDy;
    collapsedShadowRadius = textAppearance.shadowRadius;

    // Cancel pending async fetch, if any, and replace with a new one.
    if (collapsedFontCallback != null) {
      collapsedFontCallback.cancel();
    }
    collapsedFontCallback =
        new CancelableFontCallback(
            new ApplyFont() {
              @Override
              public void apply(Typeface font) {
                setCollapsedTypeface(font);
              }
            },
            textAppearance.getFallbackFont());
    textAppearance.getFontAsync(view.getContext(), collapsedFontCallback);

    recalculate();
  }

  public void setExpandedTextAppearance(int resId) {
    TextAppearance textAppearance = new TextAppearance(view.getContext(), resId);
    if (textAppearance.textColor != null) {
      expandedTextColor = textAppearance.textColor;
    }
    if (textAppearance.textSize != 0) {
      expandedTextSize = textAppearance.textSize;
    }
    if (textAppearance.shadowColor != null) {
      expandedShadowColor = textAppearance.shadowColor;
    }
    expandedShadowDx = textAppearance.shadowDx;
    expandedShadowDy = textAppearance.shadowDy;
    expandedShadowRadius = textAppearance.shadowRadius;

    // Cancel pending async fetch, if any, and replace with a new one.
    if (expandedFontCallback != null) {
      expandedFontCallback.cancel();
    }
    expandedFontCallback =
        new CancelableFontCallback(
            new ApplyFont() {
              @Override
              public void apply(Typeface font) {
                setExpandedTypeface(font);
              }
            },
            textAppearance.getFallbackFont());
    textAppearance.getFontAsync(view.getContext(), expandedFontCallback);

    recalculate();
  }

  // BEGIN MODIFICATION: getter and setter method for number of max lines
  void setMaxLines(int maxLines) {
    if (maxLines != this.maxLines) {
      this.maxLines = maxLines;
      clearTexture();
      recalculate();
    }
  }

  int getMaxLines() {
    return maxLines;
  }
  // END MODIFICATION

  // BEGIN MODIFICATION: getter and setter methods for line spacing
  void setLineSpacingExtra(float lineSpacingExtra) {
    if (lineSpacingExtra != this.lineSpacingExtra) {
      this.lineSpacingExtra = lineSpacingExtra;
      clearTexture();
      recalculate();
    }
  }

  float getLineSpacingExtra() {
    return lineSpacingExtra;
  }

  void setLineSpacingMultiplier(float lineSpacingMultiplier) {
    if (lineSpacingMultiplier != this.lineSpacingMultiplier) {
      this.lineSpacingMultiplier = lineSpacingMultiplier;
      clearTexture();
      recalculate();
    }
  }

  float getLineSpacingMultiplier() {
    return lineSpacingMultiplier;
  }
  // END MODIFICATION

  public void setCollapsedTypeface(Typeface typeface) {
    if (setCollapsedTypefaceInternal(typeface)) {
      recalculate();
    }
  }

  public void setExpandedTypeface(Typeface typeface) {
    if (setExpandedTypefaceInternal(typeface)) {
      recalculate();
    }
  }

  public void setTypefaces(Typeface typeface) {
    boolean collapsedFontChanged = setCollapsedTypefaceInternal(typeface);
    boolean expandedFontChanged = setExpandedTypefaceInternal(typeface);
    if (collapsedFontChanged || expandedFontChanged) {
      recalculate();
    }
  }

  @SuppressWarnings("ReferenceEquality") // Matches the Typeface comparison in TextView
  private boolean setCollapsedTypefaceInternal(Typeface typeface) {
    // Explicit Typeface setting cancels pending async fetch, if any, to avoid old font overriding
    // already updated one when async op comes back after a while.
    if (collapsedFontCallback != null) {
      collapsedFontCallback.cancel();
    }
    if (collapsedTypeface != typeface) {
      collapsedTypeface = typeface;
      return true;
    }
    return false;
  }

  @SuppressWarnings("ReferenceEquality") // Matches the Typeface comparison in TextView
  private boolean setExpandedTypefaceInternal(Typeface typeface) {
    // Explicit Typeface setting cancels pending async fetch, if any, to avoid old font overriding
    // already updated one when async op comes back after a while.
    if (expandedFontCallback != null) {
      expandedFontCallback.cancel();
    }
    if (expandedTypeface != typeface) {
      expandedTypeface = typeface;
      return true;
    }
    return false;
  }

  public Typeface getCollapsedTypeface() {
    return collapsedTypeface != null ? collapsedTypeface : Typeface.DEFAULT;
  }

  public Typeface getExpandedTypeface() {
    return expandedTypeface != null ? expandedTypeface : Typeface.DEFAULT;
  }

  /**
   * Set the value indicating the current scroll value. This decides how much of the background will
   * be displayed, as well as the title metrics/positioning.
   *
   * <p>A value of {@code 0.0} indicates that the layout is fully expanded. A value of {@code 1.0}
   * indicates that the layout is fully collapsed.
   */
  public void setExpansionFraction(float fraction) {
    fraction = MathUtils.clamp(fraction, 0f, 1f);

    if (fraction != expandedFraction) {
      expandedFraction = fraction;
      calculateCurrentOffsets();
    }
  }

  public final boolean setState(final int[] state) {
    this.state = state;

    if (isStateful()) {
      recalculate();
      return true;
    }

    return false;
  }

  public final boolean isStateful() {
    return (collapsedTextColor != null && collapsedTextColor.isStateful())
        || (expandedTextColor != null && expandedTextColor.isStateful());
  }

  public float getExpansionFraction() {
    return expandedFraction;
  }

  public float getCollapsedTextSize() {
    return collapsedTextSize;
  }

  public float getExpandedTextSize() {
    return expandedTextSize;
  }

  private void calculateCurrentOffsets() {
    calculateOffsets(expandedFraction);
  }

  private void calculateOffsets(final float fraction) {
    interpolateBounds(fraction);
    currentDrawX = lerp(expandedDrawX, collapsedDrawX, fraction, positionInterpolator);
    currentDrawY = lerp(expandedDrawY, collapsedDrawY, fraction, positionInterpolator);

    setInterpolatedTextSize(
        lerp(expandedTextSize, collapsedTextSize, fraction, textSizeInterpolator));

    // BEGIN MODIFICATION: set text blending
    setCollapsedTextBlend(1 - lerp(0, 1, 1 - fraction, AnimationUtils
        .FAST_OUT_SLOW_IN_INTERPOLATOR));
    setExpandedTextBlend(lerp(1, 0, fraction, AnimationUtils
        .FAST_OUT_SLOW_IN_INTERPOLATOR));
    // END MODIFICATION

    if (collapsedTextColor != expandedTextColor) {
      // If the collapsed and expanded text colors are different, blend them based on the
      // fraction
      textPaint.setColor(
          blendColors(getCurrentExpandedTextColor(), getCurrentCollapsedTextColor(), fraction));
    } else {
      textPaint.setColor(getCurrentCollapsedTextColor());
    }

    textPaint.setShadowLayer(
        lerp(expandedShadowRadius, collapsedShadowRadius, fraction, null),
        lerp(expandedShadowDx, collapsedShadowDx, fraction, null),
        lerp(expandedShadowDy, collapsedShadowDy, fraction, null),
        blendColors(
            getCurrentColor(expandedShadowColor), getCurrentColor(collapsedShadowColor), fraction));

    ViewCompat.postInvalidateOnAnimation(view);
  }

  @ColorInt
  private int getCurrentExpandedTextColor() {
    return getCurrentColor(expandedTextColor);
  }

  @ColorInt
  public int getCurrentCollapsedTextColor() {
    return getCurrentColor(collapsedTextColor);
  }

  @ColorInt
  private int getCurrentColor(@Nullable ColorStateList colorStateList) {
    if (colorStateList == null) {
      return 0;
    }
    if (state != null) {
      return colorStateList.getColorForState(state, 0);
    }
    return colorStateList.getDefaultColor();
  }

  private void calculateBaseOffsets() {
    final float currentTextSize = this.currentTextSize;

    // We then calculate the collapsed text size, using the same logic
    calculateUsingTextSize(collapsedTextSize);

    // BEGIN MODIFICATION: set mTextToDrawCollapsed and calculate width using it
    mTextToDrawCollapsed = textToDraw;
    float width = mTextToDrawCollapsed != null ?
        textPaint.measureText(mTextToDrawCollapsed, 0, mTextToDrawCollapsed.length()) : 0;
    // END MODIFICATION


    final int collapsedAbsGravity =
        GravityCompat.getAbsoluteGravity(
            collapsedTextGravity,
            isRtl ? ViewCompat.LAYOUT_DIRECTION_RTL : ViewCompat.LAYOUT_DIRECTION_LTR);

    // BEGIN MODIFICATION: calculate height and Y position using mTextLayout
    float textHeight = mTextLayout != null ? mTextLayout.getHeight() : 0;

    switch (collapsedAbsGravity & Gravity.VERTICAL_GRAVITY_MASK) {
      case Gravity.BOTTOM:
        collapsedDrawY = collapsedBounds.bottom - textHeight;
        break;
      case Gravity.TOP:
        collapsedDrawY = collapsedBounds.top;
        break;
      case Gravity.CENTER_VERTICAL:
      default:
        float textOffset = (textHeight / 2);
        collapsedDrawY = collapsedBounds.centerY() - textOffset;
        break;
    }
    // END MODIFICATION

    switch (collapsedAbsGravity & GravityCompat.RELATIVE_HORIZONTAL_GRAVITY_MASK) {
      case Gravity.CENTER_HORIZONTAL:
        collapsedDrawX = collapsedBounds.centerX() - (width / 2);
        break;
      case Gravity.RIGHT:
        collapsedDrawX = collapsedBounds.right - width;
        break;
      case Gravity.LEFT:
      default:
        collapsedDrawX = collapsedBounds.left;
        break;
    }

    calculateUsingTextSize(expandedTextSize);


    // BEGIN MODIFICATION: calculate width using mTextLayout based on first line and store that padding
    width = mTextLayout != null ? mTextLayout.getLineWidth(0) : 0;
    mExpandedFirstLineDrawX = mTextLayout != null ? mTextLayout.getLineLeft(0) : 0;
    // END MODIFICATION

    final int expandedAbsGravity =
        GravityCompat.getAbsoluteGravity(
            expandedTextGravity,
            isRtl ? ViewCompat.LAYOUT_DIRECTION_RTL : ViewCompat.LAYOUT_DIRECTION_LTR);


    // BEGIN MODIFICATION: calculate height and Y position using mTextLayout
    textHeight = mTextLayout != null ? mTextLayout.getHeight() : 0;
    switch (expandedAbsGravity & Gravity.VERTICAL_GRAVITY_MASK) {
      case Gravity.BOTTOM:
        expandedDrawY = expandedBounds.bottom - textHeight;
        break;
      case Gravity.TOP:
        expandedDrawY = expandedBounds.top;
        break;
      case Gravity.CENTER_VERTICAL:
      default:
        float textOffset = (textHeight / 2);
        expandedDrawY = expandedBounds.centerY() - textOffset;
        break;
    }
    // END MODIFICATION


    switch (expandedAbsGravity & GravityCompat.RELATIVE_HORIZONTAL_GRAVITY_MASK) {
      case Gravity.CENTER_HORIZONTAL:
        expandedDrawX = expandedBounds.centerX() - (width / 2);
        break;
      case Gravity.RIGHT:
        expandedDrawX = expandedBounds.right - width;
        break;
      case Gravity.LEFT:
      default:
        expandedDrawX = expandedBounds.left;
        break;
    }

    // The bounds have changed so we need to clear the texture
    clearTexture();
    // Now reset the text size back to the original
    setInterpolatedTextSize(currentTextSize);
  }

  private void interpolateBounds(float fraction) {
    currentBounds.left =
        lerp(expandedBounds.left, collapsedBounds.left, fraction, positionInterpolator);
    currentBounds.top = lerp(expandedDrawY, collapsedDrawY, fraction, positionInterpolator);
    currentBounds.right =
        lerp(expandedBounds.right, collapsedBounds.right, fraction, positionInterpolator);
    currentBounds.bottom =
        lerp(expandedBounds.bottom, collapsedBounds.bottom, fraction, positionInterpolator);
  }

  public void draw(Canvas canvas) {
    final int saveCount = canvas.save();

    if (textToDraw != null && drawTitle) {
      float x = currentDrawX;
      float y = currentDrawY;

      final boolean drawTexture = useTexture && expandedTitleTexture != null;

      final float ascent;
      // MODIFICATION: removed now unused "descent" variable declaration
      if (drawTexture) {
        ascent = textureAscent * scale;
      } else {
        ascent = textPaint.ascent() * scale;
      }

      if (DEBUG_DRAW) {
        // Just a debug tool, which drawn a magenta rect in the text bounds
        canvas.drawRect(currentBounds.left, y, currentBounds.right,
            y + mTextLayout.getHeight() * scale,
            DEBUG_DRAW_PAINT);
      }

      //TODO restore if not working
//      if (drawTexture) {
//        y += ascent;
//      }
      // END MODIFICATION

      if (scale != 1f) {
        canvas.scale(scale, scale, x, y);
      }
      // MODIFICATION: new draw

      final float currentExpandedX = currentDrawX + mTextLayout.getLineLeft(0) - mExpandedFirstLineDrawX * 2;

      if (drawTexture) {
        // If we should use a texture, draw it instead of text
        // Expanded text
        texturePaint.setAlpha((int) (mExpandedTextBlend * 255));
        canvas.drawBitmap(expandedTitleTexture, currentExpandedX, y, texturePaint);
        // Collapsed text
        texturePaint.setAlpha((int) (mCollapsedTextBlend * 255));
        canvas.drawBitmap(mCollapsedTitleTexture, x, y, texturePaint);
        // Cross-section between both texts (should stay at alpha = 255)
        texturePaint.setAlpha(255);
        canvas.drawBitmap(mCrossSectionTitleTexture, x, y, texturePaint);
      } else {
        // positon expanded text appropriately
        canvas.translate(currentExpandedX, y);
        // Expanded text
        textPaint.setAlpha((int) (mExpandedTextBlend * 255));
        mTextLayout.draw(canvas);

        // position the overlays
        canvas.translate(x - currentExpandedX, 0);

        // Collapsed text
        textPaint.setAlpha((int) (mCollapsedTextBlend * 255));
        canvas.drawText(mTextToDrawCollapsed, 0, mTextToDrawCollapsed.length(), 0,
            -ascent / scale, textPaint);
        // BEGIN MODIFICATION
        // Remove ellipsis for Cross-section animation
        String tmp = mTextToDrawCollapsed.toString().trim();
        if (tmp.endsWith("\u2026")) {
          tmp = tmp.substring(0, tmp.length() - 1);
        }
        // Cross-section between both texts (should stay at alpha = 255)
        textPaint.setAlpha(255);
        canvas.drawText(tmp, 0, mTextLayout.getLineEnd(0) <= tmp.length() ? mTextLayout.getLineEnd(0) : tmp.length(), 0, -ascent / scale, textPaint);
      }
      // END MODIFICATION
    }

    canvas.restoreToCount(saveCount);
  }

  private boolean calculateIsRtl(CharSequence text) {
    final boolean defaultIsRtl =
        ViewCompat.getLayoutDirection(view) == ViewCompat.LAYOUT_DIRECTION_RTL;
    return (defaultIsRtl
        ? TextDirectionHeuristicsCompat.FIRSTSTRONG_RTL
        : TextDirectionHeuristicsCompat.FIRSTSTRONG_LTR)
        .isRtl(text, 0, text.length());
  }

  private void setInterpolatedTextSize(float textSize) {
    calculateUsingTextSize(textSize);

    // Use our texture if the scale isn't 1.0
    useTexture = USE_SCALING_TEXTURE && scale != 1f;

    if (useTexture) {
      // Make sure we have an expanded texture if needed
      ensureExpandedTexture();
      // BEGIN MODIFICATION: added collapsed and cross section textures
      ensureCollapsedTexture();
      ensureCrossSectionTexture();
    }

    ViewCompat.postInvalidateOnAnimation(view);
    // END MODIFICATION
  }

  // BEGIN MODIFICATION: new setCollapsedTextBlend and setExpandedTextBlend methods
  private void setCollapsedTextBlend(float blend) {
    mCollapsedTextBlend = blend;
    ViewCompat.postInvalidateOnAnimation(view);
  }

  private void setExpandedTextBlend(float blend) {
    mExpandedTextBlend = blend;
    ViewCompat.postInvalidateOnAnimation(view);
  }
  // END MODIFICATION

  @SuppressWarnings("ReferenceEquality") // Matches the Typeface comparison in TextView
  private void calculateUsingTextSize(final float textSize) {
    if (text == null) {
      return;
    }

    final float collapsedWidth = collapsedBounds.width();
    final float expandedWidth = expandedBounds.width();

    final float availableWidth;
    final float newTextSize;
    boolean updateDrawText = false;
    // BEGIN MODIFICATION: Add maxLines variable
    int maxLines;
    // END MODIFICATION

    if (isClose(textSize, collapsedTextSize)) {
      newTextSize = collapsedTextSize;
      scale = 1f;
      if (currentTypeface != collapsedTypeface) {
        currentTypeface = collapsedTypeface;
        updateDrawText = true;
      }
      availableWidth = collapsedWidth;
      // BEGIN MODIFICATION: Set maxLines variable
      maxLines = 1;
      // END MODIFICATION
    } else {
      newTextSize = expandedTextSize;
      if (currentTypeface != expandedTypeface) {
        currentTypeface = expandedTypeface;
        updateDrawText = true;
      }
      if (isClose(textSize, expandedTextSize)) {
        // If we're close to the expanded text size, snap to it and use a scale of 1
        scale = 1f;
      } else {
        // Else, we'll scale down from the expanded text size
        scale = textSize / expandedTextSize;
      }

      final float textSizeRatio = collapsedTextSize / expandedTextSize;
      // This is the size of the expanded bounds when it is scaled to match the
      // collapsed text size
      final float scaledDownWidth = expandedWidth * textSizeRatio;

      if (scaledDownWidth > collapsedWidth) {
        // If the scaled down size is larger than the actual collapsed width, we need to
        // cap the available width so that when the expanded text scales down, it matches
        // the collapsed width

        // BEGIN MODIFICATION:
        availableWidth = expandedWidth;
        // END MODIFICATION
      } else {
        // Otherwise we'll just use the expanded width
        availableWidth = expandedWidth;
      }

      // BEGIN MODIFICATION: Set maxLines variable
      maxLines = this.maxLines;
      // END MODIFICATION
    }

    if (availableWidth > 0) {
      updateDrawText = (currentTextSize != newTextSize) || boundsChanged || updateDrawText;
      currentTextSize = newTextSize;
      boundsChanged = false;
    }

    if (textToDraw == null || updateDrawText) {
      textPaint.setTextSize(currentTextSize);
      textPaint.setTypeface(currentTypeface);


      // BEGIN MODIFICATION: Text layout creation and text truncation
      StaticLayout layout = new StaticLayout(text, textPaint, (int) availableWidth,
          Layout.Alignment.ALIGN_NORMAL, lineSpacingMultiplier, lineSpacingExtra, false);
      CharSequence truncatedText;
      if (layout.getLineCount() > maxLines) {
        int lastLine = maxLines - 1;
        CharSequence textBefore =
            lastLine > 0 ? text.subSequence(0, layout.getLineEnd(lastLine - 1)) : "";
        CharSequence lineText = text.subSequence(layout.getLineStart(lastLine),
            layout.getLineEnd(lastLine));
        // if last char in line is space, move it behind the ellipsis
        CharSequence lineEnd = "";
        if (lineText.charAt(lineText.length() - 1) == ' ') {
          lineEnd = lineText.subSequence(lineText.length() - 1, lineText.length());
          lineText = lineText.subSequence(0, lineText.length() - 1);
        }
        // insert ellipsis character
        lineText = TextUtils.concat(lineText, "\u2026", lineEnd);
        // if the text is too long, truncate it
        CharSequence truncatedLineText = TextUtils.ellipsize(lineText, textPaint,
            availableWidth, TextUtils.TruncateAt.END);
        truncatedText = TextUtils.concat(textBefore, truncatedLineText);

      } else {
        truncatedText = text;
      }
      if (!TextUtils.equals(truncatedText, textToDraw)) {
        textToDraw = truncatedText;
        isRtl = calculateIsRtl(textToDraw);
      }

      final Layout.Alignment alignment;

      // Don't rectify gravity for RTL languages, Layout.Alignment does it already.
      switch (expandedTextGravity & GravityCompat.RELATIVE_HORIZONTAL_GRAVITY_MASK) {
        case Gravity.CENTER_HORIZONTAL:
          alignment = Layout.Alignment.ALIGN_CENTER;
          break;
        case Gravity.RIGHT:
        case Gravity.END:
          alignment = Layout.Alignment.ALIGN_OPPOSITE;
          break;
        case Gravity.LEFT:
        case Gravity.START:
        default:
          alignment = Layout.Alignment.ALIGN_NORMAL;
          break;
      }

      mTextLayout = new StaticLayout(textToDraw, textPaint, (int) availableWidth,
          alignment, lineSpacingMultiplier, lineSpacingExtra, false);
      // END MODIFICATION
    }
  }

  private void ensureExpandedTexture() {
    if (expandedTitleTexture != null || expandedBounds.isEmpty() || TextUtils.isEmpty(textToDraw)) {
      return;
    }

    calculateOffsets(0f);

    // BEGIN MODIFICATION: Calculate width and height using mTextLayout and remove
    // mTextureAscent and mTextureDescent assignment
    final int w = mTextLayout.getWidth();
    final int h = mTextLayout.getHeight();
    // END MODIFICATION

    if (w <= 0 || h <= 0) {
      return; // If the width or height are 0, return
    }

    expandedTitleTexture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

    // BEGIN MODIFICATION: Draw text using mTextLayout
    Canvas c = new Canvas(expandedTitleTexture);
    mTextLayout.draw(c);
    // END MODIFICATION

    if (texturePaint == null) {
      // Make sure we have a paint
      texturePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    }
  }

  // BEGIN MODIFICATION: new ensureCollapsedTexture and ensureCrossSectionTexture methods
  private void ensureCollapsedTexture() {
    if (mCollapsedTitleTexture != null || collapsedBounds.isEmpty()
        || TextUtils.isEmpty(textToDraw)) {
      return;
    }
    calculateOffsets(0f);
    final int w = Math.round(textPaint.measureText(textToDraw, 0, textToDraw.length()));
    final int h = Math.round(textPaint.descent() - textPaint.ascent());
    if (w <= 0 && h <= 0) {
      return; // If the width or height are 0, return
    }
    mCollapsedTitleTexture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(mCollapsedTitleTexture);
    c.drawText(mTextToDrawCollapsed, 0, mTextToDrawCollapsed.length(), 0,
        -textPaint.ascent() / scale, textPaint);
    if (texturePaint == null) {
      // Make sure we have a paint
      texturePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    }
  }

  private void ensureCrossSectionTexture() {
    if (mCrossSectionTitleTexture != null || collapsedBounds.isEmpty()
        || TextUtils.isEmpty(textToDraw)) {
      return;
    }
    calculateOffsets(0f);
    final int w = Math.round(textPaint.measureText(textToDraw, mTextLayout.getLineStart(0),
        mTextLayout.getLineEnd(0)));
    final int h = Math.round(textPaint.descent() - textPaint.ascent());
    if (w <= 0 && h <= 0) {
      return; // If the width or height are 0, return
    }
    mCrossSectionTitleTexture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(mCrossSectionTitleTexture);
    String tmp = mTextToDrawCollapsed.toString().trim();
    if (tmp.endsWith("\u2026")) {
      tmp = tmp.substring(0, tmp.length() - 1);
    }
    c.drawText(tmp, 0,
        mTextLayout.getLineEnd(0) <= tmp.length() ? mTextLayout.getLineEnd(0) : tmp.length(), 0, -textPaint.ascent() / scale, textPaint);
    if (texturePaint == null) {
      // Make sure we have a paint
      texturePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    }
  }
  // END MODIFICATION

  public void recalculate() {
    if (view.getHeight() > 0 && view.getWidth() > 0) {
      // If we've already been laid out, calculate everything now otherwise we'll wait
      // until a layout
      calculateBaseOffsets();
      calculateCurrentOffsets();
    }
  }

  /**
   * Set the title to display
   *
   * @param text
   */
  public void setText(CharSequence text) {
    if (text == null || !TextUtils.equals(this.text, text)) {
      this.text = text;
      textToDraw = null;
      clearTexture();
      recalculate();
    }
  }

  public CharSequence getText() {
    return text;
  }

  private void clearTexture() {
    if (expandedTitleTexture != null) {
      expandedTitleTexture.recycle();
      expandedTitleTexture = null;
    }

    // BEGIN MODIFICATION: clear other textures
    if (mCollapsedTitleTexture != null) {
      mCollapsedTitleTexture.recycle();
      mCollapsedTitleTexture = null;
    }
    if (mCrossSectionTitleTexture != null) {
      mCrossSectionTitleTexture.recycle();
      mCrossSectionTitleTexture = null;
    }
    // END MODIFICATION
  }

  /**
   * Returns true if {@code value} is 'close' to it's closest decimal value. Close is currently
   * defined as it's difference being < 0.001.
   */
  private static boolean isClose(float value, float targetValue) {
    return Math.abs(value - targetValue) < 0.001f;
  }

  public ColorStateList getExpandedTextColor() {
    return expandedTextColor;
  }

  public ColorStateList getCollapsedTextColor() {
    return collapsedTextColor;
  }

  /**
   * Blend {@code color1} and {@code color2} using the given ratio.
   *
   * @param ratio of which to blend. 0.0 will return {@code color1}, 0.5 will give an even blend,
   *              1.0 will return {@code color2}.
   */
  private static int blendColors(int color1, int color2, float ratio) {
    final float inverseRatio = 1f - ratio;
    float a = (Color.alpha(color1) * inverseRatio) + (Color.alpha(color2) * ratio);
    float r = (Color.red(color1) * inverseRatio) + (Color.red(color2) * ratio);
    float g = (Color.green(color1) * inverseRatio) + (Color.green(color2) * ratio);
    float b = (Color.blue(color1) * inverseRatio) + (Color.blue(color2) * ratio);
    return Color.argb((int) a, (int) r, (int) g, (int) b);
  }

  private static float lerp(
      float startValue, float endValue, float fraction, TimeInterpolator interpolator) {
    if (interpolator != null) {
      fraction = interpolator.getInterpolation(fraction);
    }
    return AnimationUtils.lerp(startValue, endValue, fraction);
  }

  private static boolean rectEquals(Rect r, int left, int top, int right, int bottom) {
    return !(r.left != left || r.top != top || r.right != right || r.bottom != bottom);
  }
}
