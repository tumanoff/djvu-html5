//C- -------------------------------------------------------------------
//C- Java DjVu (r) (v. 0.8)
//C- Copyright (c) 2004-2005 LizardTech, Inc.  All Rights Reserved.
//C- Java DjVu is protected by U.S. Pat. No.C- 6,058,214 and patents
//C- pending.
//C-
//C- This software is subject to, and may be distributed under, the
//C- GNU General Public License, Version 2. The license should have
//C- accompanied the software or you may obtain a copy of the license
//C- from the Free Software Foundation at http://www.fsf.org .
//C-
//C- The computer code originally released by LizardTech under this
//C- license and unmodified by other parties is deemed "the LIZARDTECH
//C- ORIGINAL CODE."  Subject to any third party intellectual property
//C- claims, LizardTech grants recipient a worldwide, royalty-free,
//C- non-exclusive license to make, use, sell, or otherwise dispose of
//C- the LIZARDTECH ORIGINAL CODE or of programs derived from the
//C- LIZARDTECH ORIGINAL CODE in compliance with the terms of the GNU
//C- General Public License.   This grant only confers the right to
//C- infringe patent claims underlying the LIZARDTECH ORIGINAL CODE to
//C- the extent such infringement is reasonably necessary to enable
//C- recipient to make, have made, practice, sell, or otherwise dispose
//C- of the LIZARDTECH ORIGINAL CODE (or portions thereof) and not to
//C- any greater extent that may be necessary to utilize further
//C- modifications or combinations.
//C-
//C- The LIZARDTECH ORIGINAL CODE is provided "AS IS" WITHOUT WARRANTY
//C- OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
//C- TO ANY WARRANTY OF NON-INFRINGEMENT, OR ANY IMPLIED WARRANTY OF
//C- MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.
//C-
//C- In addition, as a special exception, LizardTech Inc. gives permission
//C- to link the code of this program with the proprietary Java
//C- implementation provided by Sun (or other vendors as well), and
//C- distribute linked combinations including the two. You must obey the
//C- GNU General Public License in all respects for all of the code used
//C- other than the proprietary Java implementation. If you modify this
//C- file, you may extend this exception to your version of the file, but
//C- you are not obligated to do so. If you do not wish to do so, delete
//C- this exception statement from your version.
//C- -------------------------------------------------------------------
//C- Developed by Bill C. Riemers, Foxtrot Technologies Inc. as work for
//C- hire under US copyright laws.
//C- -------------------------------------------------------------------
//
package com.lizardtech.djvu;

import static com.lizardtech.djvu.GMap.BYTES_PER_PIXEL;

/**
 * DOCUMENT ME!
 *
 * @author $author$
 * @version $Revision: 1.10 $
 */
public final class GPixelReference
  extends GPixel
{
  //~ Instance fields --------------------------------------------------------

  private GPixmap parent;

  /** The current byte position in the data array. */
  private int offset;
  
  private final int redOffset;
  private final int greenOffset;
  private final int blueOffset;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a createGPixelReference object.
   *
   * @param parent the image map to refere to
   * @param offset the initial pixel position to refere to
   */
  public GPixelReference(
    final GPixmap parent,
    final int  offset)
  {
    this.parent   = parent;
    this.offset   = offset * BYTES_PER_PIXEL;
    blueOffset=parent.getBlueOffset();
    greenOffset=parent.getGreenOffset();
    redOffset=parent.getRedOffset();
  }

  /**
   * Creates a createGPixelReference object.
   *
   * @param parent DOCUMENT ME!
   * @param row DOCUMENT ME!
   * @param column DOCUMENT ME!
   */
  public GPixelReference(
    final GPixmap parent,
    final int  row,
    final int  column)
  {
    this.parent   = parent;
    this.offset   = (parent.rowOffset(row) + column) * BYTES_PER_PIXEL;
    blueOffset=parent.getBlueOffset();
    greenOffset=parent.getGreenOffset();
    redOffset=parent.getRedOffset();
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Copy the pixel values.
   *
   * @param ref pixel to copy
   */
  public final void setPixels(final GPixelReference ref,int length)
  {
      System.arraycopy(ref.parent.data,ref.offset,parent.data,offset,length*BYTES_PER_PIXEL);
      ref.incOffset(length);
      incOffset(length);
  }

  /**
   * Set the map image pixel we are refering to.
   *
   * @param offset pixel position
   */
  public void setOffset(int offset)
  {
    this.offset = offset * BYTES_PER_PIXEL;
  }

  /**
   * Set the map image pixel we are refering to.
   *
   * @param row vertical position
   * @param column horizontal position
   */
  public void setOffset(
    int row,
    int column)
  {
    this.offset = (parent.rowOffset(row) + column) * BYTES_PER_PIXEL;
  }

  public int getOffset() {
	return offset;
  }

  /**
   * Convert the following number of pixels from YCC to RGB. The offset will
   * be advanced to the end.
   *
   * @param count The number of pixels to convert.
   */
  public void YCC_to_RGB(int count)
  {
    if(parent.isRampNeeded())
    {
        throw new IllegalStateException("YCC_to_RGB only legal with three colors");
    }
    while(count-- > 0)
    {
      final int y                = (byte) parent.data.get(offset);
      final int b                = (byte) parent.data.get(offset + 1);
      final int r                = (byte) parent.data.get(offset + 2);
      final int t2               = r + (r >> 1);
      final int t3               = (y + 128) - (b >> 2);
      final int b0               = t3 + (b << 1);
      parent.data.set(offset + blueOffset, (b0 < 255) ? ((b0 > 0) ? b0 : 0) : 255);

      final int g0 = t3 - (t2 >> 1);
      parent.data.set(offset + greenOffset, (g0 < 255) ? ((g0 > 0) ? g0 : 0) : 255);

      final int r0 = y + 128 + t2;
      parent.data.set(offset + redOffset, (r0 < 255) ? ((r0 > 0) ? r0 : 0) : 255);

      offset += BYTES_PER_PIXEL;
    }
  }

  /**
   * Set the blue, green, and red values of the current pixel.
   *
   * @param blue pixel value
   * @param green pixel value
   * @param red pixel value
   */
  @Override
public void setBGR(
    final int blue,
    final int green,
    final int red)
  {
    parent.data.set(offset + blueOffset, (byte) blue);
    parent.data.set(offset + greenOffset, (byte) green);
    parent.data.set(offset + redOffset, (byte) red);
  }

  /**
   * Set the blue pixel value.
   *
   * @param blue pixel value
   */
  @Override
public void setBlue(final byte blue)
  {
	  parent.data.set(offset + blueOffset, blue);
  }

  /**
   * Query the blue pixel value.
   *
   * @return blue pixel value
   */
  @Override
public byte blueByte()
  {
    return (byte) parent.data.get(offset+blueOffset);
  }

  /**
   * Create a duplicate of this GPixelReference.
   *
   * @return the newly created GPixelReference
   */
  public GPixelReference duplicate()
  {
    return new GPixelReference(parent, offset);
  }

  /**
   * Set the green pixel value.
   *
   * @param green pixel value
   */
  @Override
public void setGreen(final byte green)
  {
	  parent.data.set(offset + greenOffset, green);
  }

  /**
   * Query the green pixel value.
   *
   * @return green pixel value
   */
  @Override
public byte greenByte()
  {
    return (byte) parent.data.get(offset + greenOffset);
  }

  /**
   * Step to the next pixel.  Care should be taken when stepping past the end of a row.
   */
  public void incOffset()
  {
    this.offset += BYTES_PER_PIXEL;
  }

  /**
   * Skip past the specified number of pixels.  Care should be taken when stepping 
   * past the end of a row.
   *
   * @param offset number of pixels to step past.
   */
  public void incOffset(final int offset)
  {
    this.offset += (BYTES_PER_PIXEL * offset);
  }

  /**
   * Set the red pixel value.
   *
   * @param red pixel value
   */
  @Override
public void setRed(final byte red)
  {
	  parent.data.set(offset + redOffset, red);
  }

  /**
   * Query the red pixel value.
   *
   * @return red pixel value
   */
  @Override
public byte redByte()
  {
    return (byte) parent.data.get(offset + redOffset);
  }
}
