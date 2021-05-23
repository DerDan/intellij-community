// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.editor.impl.view;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.impl.FontInfo;
import com.intellij.util.BitUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * GlyphVector-based text fragment. Used for non-Latin text or when ligatures are enabled
 */
class ComplexTextFragment extends TextFragment {
  private static final Logger LOG = Logger.getInstance(ComplexTextFragment.class);
  private static final double CLIP_MARGIN = 1e4;

  @NotNull
  private final GlyphVector myGlyphVector;
  private GlyphVector myAccentVector = null;
  private final short @Nullable [] myCodePoint2Offset; // Start offset of each Unicode code point in the fragment
  // (null if each code point takes one char).
  // We expect no more than 1025 chars in a fragment, so 'short' should be enough.
  private float myEffectShiftX;
  private int myEffectShiftY;
  private int myGroupModulo = 0;
  private int myGroupModuloStart = 0;
  private int myGroupHook = 0;

  ComplexTextFragment(char @NotNull [] lineChars, int start, int end, boolean isRtl, @NotNull FontInfo fontInfo) {
    this(lineChars, start, end, isRtl, fontInfo, 0);
  }

  ComplexTextFragment(char @NotNull [] lineChars, int start, int end, boolean isRtl, @NotNull FontInfo fontInfo, int groupCount) {
    super(end - start);
    assert start >= 0;
    assert end <= lineChars.length;
    assert start < end;
    myGlyphVector = FontLayoutService.getInstance().layoutGlyphVector(fontInfo.getFont(), fontInfo.getFontRenderContext(),
                                                                      lineChars, start, end, isRtl);
    int numChars = end - start;
    int numGlyphs = myGlyphVector.getNumGlyphs();
    float totalWidth = (float)myGlyphVector.getGlyphPosition(numGlyphs).getX();
    myCharPositions[numChars - 1] = totalWidth;
    int lastCharIndex = -1;
    float lastX = isRtl ? totalWidth : 0;
    float prevX = lastX;
    // Here we determine coordinates for boundaries between characters.
    // They will be used to place caret, last boundary coordinate is also defining the width of text fragment.
    //
    // We expect these positions to be ordered, so that when caret moves through text characters in some direction, corresponding text
    // offsets change monotonously (within the same-directionality fragment).
    //
    // Special case that we must account for is a ligature, when several adjacent characters are represented as a single glyph.
    // In a glyph vector this glyph is associated with the first character,
    // other characters either don't have an associated glyph, or they are associated with empty glyphs.
    // (in RTL case real glyph will be associated with first logical character, i.e. last visual character)
    for (int i = 0; i < numGlyphs; i++) {
      int visualGlyphIndex = isRtl ? numGlyphs - 1 - i : i;
      int charIndex = myGlyphVector.getGlyphCharIndex(visualGlyphIndex);
      if (charIndex > lastCharIndex) {
        Rectangle2D bounds = myGlyphVector.getGlyphLogicalBounds(visualGlyphIndex).getBounds2D();
        if (!bounds.isEmpty()) {
          if (charIndex > lastCharIndex + 1) {
            for (int j = Math.max(0, lastCharIndex); j < charIndex; j++) {
              setCharPosition(j, prevX + (lastX - prevX) * (j - lastCharIndex + 1) / (charIndex - lastCharIndex), isRtl, numChars);
            }
          }
          float newX = isRtl ? Math.min(lastX, (float)bounds.getMinX()) : Math.max(lastX, (float)bounds.getMaxX());
          newX = Math.max(0, Math.min(totalWidth, newX));
          setCharPosition(charIndex, newX, isRtl, numChars);
          prevX = lastX;
          lastX = newX;
          lastCharIndex = charIndex;
        }
      }
    }
    if (lastCharIndex < numChars - 1) {
      for (int j = Math.max(0, lastCharIndex); j < numChars - 1; j++) {
        setCharPosition(j, prevX + (lastX - prevX) * (j - lastCharIndex + 1) / (numChars - lastCharIndex), isRtl, numChars);
      }
    }
    int codePointCount = Character.codePointCount(lineChars, start, end - start);
    if (codePointCount == numChars) {
      myCodePoint2Offset = null;
    }
    else {
      myCodePoint2Offset = new short[codePointCount];
      int offset = 0;
      for (int i = 0; i < codePointCount; i++) {
        myCodePoint2Offset[i] = (short)(offset++);
        if (offset < numChars &&
            Character.isHighSurrogate(lineChars[start + offset - 1]) &&
            Character.isLowSurrogate(lineChars[start + offset])) {
          offset++;
        }
      }
    }
    groupNumbers(isRtl, fontInfo, groupCount, numGlyphs);
  }

  private void groupNumbers(boolean isRtl, @NotNull FontInfo fontInfo, int groupCount, int numGlyphs) {
    char[] accent = null;
    FontMetrics metrics = fontInfo.fontMetrics();
    if (!isRtl && groupCount != 0) {
      switch (fontInfo.getGroupNumbers()) {
        case SQUEEZE: {
          double gapBetween = 0.3 * fontInfo.charWidth2D(' ');
          squeezeGlyphs(numGlyphs, groupCount, gapBetween);
        }
        break;
        case SWISS:
          accent = accentChars(numGlyphs - 1, groupCount, '\'');
          break;
        case UNDER_LINED:
          calculateLined(groupCount, numGlyphs, metrics.getDescent() / 2, 0);
          break;
        case UNDER_LINED_HOOK:
          calculateLined(groupCount, numGlyphs, metrics.getDescent() / 2 + 3, -2);
          break;
        case OVER_LINED:
          calculateLined(groupCount, numGlyphs, -metrics.getAscent() - 1, 0);
          break;
        case OVER_LINED_HOOK:
          calculateLined(groupCount, numGlyphs, -metrics.getAscent() - 2, 2);
          break;
        case NONE:
        default:
          break;
      }
      if (accent != null) {
        myAccentVector = FontLayoutService.getInstance().layoutGlyphVector(fontInfo.getFont(), fontInfo.getFontRenderContext(),
                                                                           accent, 0, accent.length, false);
        myEffectShiftX = fontInfo.charWidth2D(' ') / 2;
        myEffectShiftY = metrics.getAscent() - metrics.getHeight();
      }
    }
  }

  private void calculateLined(int groupNumbers, int numGlyphs, int Y, int hook) {
    if (groupNumbers > 0) {
      myGroupModulo = groupNumbers;
      myGroupModuloStart = (1 + ((numGlyphs - 1) % (groupNumbers * 2)) - groupNumbers);
    }
    else {
      myGroupModulo = -groupNumbers;
      myGroupModuloStart = -groupNumbers;
    }
    myGroupHook = hook;
    myEffectShiftY = Y;
  }

  private static char[] accentChars(int numGlyphs, int numbers, char c) {
    if (numbers == 0 || numGlyphs == 0) {
      return null;
    }
    char[] result = new char[numGlyphs];
    int offset = 1;
    if (numbers > 0) {
      offset = numbers - (numGlyphs % numbers);
    }
    else {
      numbers = -numbers;
    }
    for (int n = 0; n < numGlyphs; n++) {
      result[n] = ((n + offset) % numbers) == 0 ? c : ' ';
    }
    return result;
  }

  private void squeezeGlyphs(int numGlyphs, int squeeze, double pixels) {
    if (squeeze == 0) {
      return;
    }
    int offset = 0;
    if (squeeze > 0) {
      offset = squeeze - (numGlyphs % squeeze);
    }
    else {
      squeeze = -squeeze;
    }
    double advance = pixels / (squeeze - 1);
    for (int glyphIndex = 0; glyphIndex < numGlyphs; glyphIndex++) {
      Point2D glyphPosition = myGlyphVector.getGlyphPosition(glyphIndex);
      double moveX = pixels / 2 - ((glyphIndex + offset) % squeeze) * advance;
      glyphPosition.setLocation(glyphPosition.getX() + moveX, glyphPosition.getY());
      myGlyphVector.setGlyphPosition(glyphIndex, glyphPosition);
    }
  }

  private void setCharPosition(int logicalCharIndex, float x, boolean isRtl, int numChars) {
    int charPosition = isRtl ? numChars - logicalCharIndex - 2 : logicalCharIndex;
    if (charPosition >= 0 && charPosition < numChars - 1) {
      myCharPositions[charPosition] = x;
    }
  }

  @Override
  boolean isRtl() {
    return BitUtil.isSet(myGlyphVector.getLayoutFlags(), GlyphVector.FLAG_RUN_RTL);
  }

  @Override
  int offsetToLogicalColumn(int offset) {
    if (myCodePoint2Offset == null) return offset;
    if (offset == getLength()) return myCodePoint2Offset.length;
    int i = Arrays.binarySearch(myCodePoint2Offset, (short)offset);
    assert i >= 0;
    return i;
  }

  // Drawing a portion of glyph vector using clipping might be not very effective
  // (we still pass all glyphs to the rendering code, and filtering by clipping might occur late in the processing,
  // on OS X larger number of glyphs passed for processing is known to slow down rendering significantly).
  // So we try to merge drawing of adjacent glyph vector fragments, if possible.
  private static ComplexTextFragment lastFragment;
  private static int lastStartColumn;
  private static int lastEndColumn;
  private static Color lastColor;
  private static float lastStartX;
  private static float lastEndX;
  private static float lastY;

  @SuppressWarnings("AssignmentToStaticFieldFromInstanceMethod")
  @Override
  public Consumer<Graphics2D> draw(float x, float y, int startColumn, int endColumn) {
    assert startColumn >= 0;
    assert endColumn <= myCharPositions.length;
    assert startColumn < endColumn;

    return g -> {
      Color color = g.getColor();
      assert color != null;
      float newX = x - getX(startColumn) + getX(endColumn);
      if (lastFragment == this && lastEndColumn == startColumn && lastEndX == x && lastY == y && color.equals(lastColor)) {
        lastEndColumn = endColumn;
        lastEndX = newX;
        return;
      }

      flushDrawingCache(g);
      lastFragment = this;
      lastStartColumn = startColumn;
      lastEndColumn = endColumn;
      lastColor = color;
      lastStartX = x;
      lastEndX = newX;
      lastY = y;
    };
  }

  private void doDraw(Graphics2D g, float x, float y, int startColumn, int endColumn) {
    updateStats(endColumn - startColumn, myCharPositions.length);
    if (startColumn == 0 && endColumn == myCharPositions.length) {
      g.drawGlyphVector(myGlyphVector, x, y);
      drawAccents(g, x, y);
      drawLined(g, x, y);
    }
    else {
      Shape savedClip = g.getClip();
      float startX = x - getX(startColumn);
      // We define clip region here assuming that glyphs do not extend further than CLIP_MARGIN pixels from baseline
      // vertically (both up and down) and horizontally (from the region defined by glyph vector's total advance)
      double xMin = x - (startColumn == 0 ? CLIP_MARGIN : 0);
      double xMax = startX + getX(endColumn) + (endColumn == myCharPositions.length ? CLIP_MARGIN : 0);
      double yMin = y - CLIP_MARGIN;
      double yMax = y + CLIP_MARGIN;
      g.clip(new Rectangle2D.Double(xMin, yMin, xMax - xMin, yMax - yMin));
      g.drawGlyphVector(myGlyphVector, startX, y);
      drawAccents(g, startX, y);
      drawLined(g, startX, y);
      g.setClip(savedClip);
    }
  }

  private void drawLined(Graphics2D g, float x, float y) {
    drawLined(g, (int)x, (int)y);
  }

  private void drawLined(Graphics2D g, int x, int y) {
    if (myGroupModulo > 0) {
      int numbers = myCharPositions.length;
      for (int n = myGroupModuloStart; n < numbers; n += myGroupModulo * 2) {
        int x1 = (n > 0) ? x + (int)myCharPositions[n - 1] : x;
        int offset2 = n - 1 + myGroupModulo;
        int x2 = x - 1 + (int)myCharPositions[(offset2 >= numbers) ? numbers - 1 : offset2];
        int distanceX = Math.abs(myGroupHook);
        g.drawLine(x1 + distanceX, y + myEffectShiftY, x2 - distanceX, y + myEffectShiftY);
        if (myGroupHook != 0) {
          g.drawLine(x1 + distanceX, y + myEffectShiftY, x1, y + myEffectShiftY + myGroupHook);
          g.drawLine(x2 - distanceX, y + myEffectShiftY, x2, y + myEffectShiftY + myGroupHook);
        }
      }
    }
  }

  private void drawAccents(Graphics2D g, float x, float y) {
    if (myAccentVector != null) {
      g.drawGlyphVector(myAccentVector, x + myEffectShiftX, y + myEffectShiftY);
    }
  }

  private int getCodePointCount() {
    return myCodePoint2Offset == null ? myCharPositions.length : myCodePoint2Offset.length;
  }

  private int visualColumnToVisualOffset(int column) {
    if (myCodePoint2Offset == null) return column;
    if (column <= 0) return 0;
    if (column >= myCodePoint2Offset.length) return getLength();
    return isRtl() ? getLength() - myCodePoint2Offset[myCodePoint2Offset.length - column] : myCodePoint2Offset[column];
  }

  @Override
  public int getLogicalColumnCount(int startColumn) {
    return getCodePointCount();
  }

  @Override
  public int getVisualColumnCount(float startX) {
    return getCodePointCount();
  }

  @Override
  public int visualColumnToOffset(float startX, int column) {
    return visualColumnToVisualOffset(column);
  }

  @Override
  public int[] xToVisualColumn(float startX, float x) {
    float relX = x - startX;
    float prevPos = 0;
    int columnCount = getCodePointCount();
    for (int i = 0; i < columnCount; i++) {
      int visualOffset = visualColumnToVisualOffset(i);
      float newPos = myCharPositions[visualOffset];
      if (relX < (newPos + prevPos) / 2) {
        return new int[]{i, relX <= prevPos ? 0 : 1};
      }
      prevPos = newPos;
    }
    return new int[]{columnCount, relX <= myCharPositions[myCharPositions.length - 1] ? 0 : 1};
  }

  @Override
  public float visualColumnToX(float startX, int column) {
    return startX + getX(visualColumnToVisualOffset(column));
  }

  public static void flushDrawingCache(Graphics2D g) {
    if (lastFragment != null) {
      g.setColor(lastColor);
      lastFragment.doDraw(g, lastStartX, lastY, lastStartColumn, lastEndColumn);
      lastFragment = null;
    }
  }

  private static long ourDrawingCount;
  private static long ourCharsProcessed;
  private static long ourGlyphsProcessed;

  private static void updateStats(int charCount, int glyphCount) {
    if (!LOG.isDebugEnabled()) return;
    ourCharsProcessed += charCount;
    ourGlyphsProcessed += glyphCount;
    if (++ourDrawingCount == 10000) {
      LOG.debug("Text rendering stats: " + ourCharsProcessed + " chars, " + ourGlyphsProcessed + " glyps, ratio - " +
                ((float)ourGlyphsProcessed) / ourCharsProcessed);
      ourDrawingCount = 0;
      ourCharsProcessed = 0;
      ourGlyphsProcessed = 0;
    }
  }
}
