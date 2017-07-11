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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.dom.client.ImageElement;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.EventListener;
import com.google.gwt.user.client.ui.Image;
import com.lizardtech.djvu.anno.DjVuAnno;
import com.lizardtech.djvu.text.DjVuText;


/**
 * <p>
 * DjVuPage decodes single page DjVu files.  This class is appropriate for
 * decoding a single page.  If an INCL chunk is contained in the page, then
 * either decoding must be done from a URL or createDataPool must be
 * overloaded as in the case of the Document class.  The general usage of
 * this class is to create an instance, set any desired values, and then
 * call decode with the relevant data. Decoding may be done either
 * asynchronously or synchronously.  By default decoding is synchronous, but
 * this may be changed by calling setAsync(true) before calling decode. If
 * you will be accessing this class from another thread while decoding is in
 * progress  then you should use asynchronous mode.
 * </p>
 * 
 * <p>
 * In synchronous mode, the decode method will not return until decoding  is
 * complete.  In asynchronous mode decode returns right away as decoding
 * continues in a separate thread.  One method to track asynchronous decode
 * progress is to register a PropertyChangeListener.  Each codec will
 * generate a signal when created.  If you need to wait for a particular
 * codec, rather than polling, use the waitForCodec method.
 * </p>
 * 
 * <p>
 * The updateMap method should be used for progressive rendering. The
 * following example shows how to progressively update an image map in a
 * separate thread.
 * </p>
 * <pre>
 * class Progressive extends Thread
 * {
 *   GMap map = null;
 *   GRect segment;
 *   int subsample;
 *   final DjVuPage page;
 * 
 *   ... define constructors and other methods here ...
 * 
 *   public void run()
 *   { 
 *     boolean repeat;
 *     do
 *     {
 *       repeat=page.isDecoding();
 *       map=page.updateMap(map,segment,subsample); // get the map
 *       // We limit our wait to 200 ms, to query updates for new
 *       // segment or subsample values.  This also limits our wait
 *       // wait time when the page is updated before waitForCodec 
 *       // obtains a lock.
 *       page.waitForCodec(progressLock,200L);
 *     } while(repeat);
 *   }
 * }
 * </pre>
 */
public class DjVuPage
{
	private static class CodecInclude {
		public Object lock;
		public Codec codec;
		public CodecInclude(Object lock, Codec codec) {
			this.lock = lock;
			this.codec = codec;
		}
	}
  //~ Static fields/initializers ---------------------------------------------

  /** This is the version of DjVu this code was written for. */
  public static final int DJVUVERSION = 20;

  /** This is the oldest version of DjVu we can hope to decode. */
  public static final int DJVUVERSION_TOO_OLD = 15;

  /** This is the newest version of DjVu we should attempt to decode. */
  public static final int DJVUVERSION_TOO_NEW = 22;

  private static HashMap<String, List<CodecInclude>> sharedIncludes = new HashMap<>();
  
  //~ Instance fields --------------------------------------------------------

  /** Lock used for accessing the annotation codec. */
  public final Object annoLock = new String("anno");

  /** Lock used for accessing the background IWPixmap codec. */
  public final Object bgIWPixmapLock = new String("bgIWPixmap");

  /** Lock used for accessing the foreground JB2Dict codec. */
  public final Object fgJb2DictLock = new String("fgJb2Dict");

  /** Lock used for accessing the foreground JB2 codec. */
  public final Object fgJb2Lock = new String("fgJb2");

  /** Lock used for accessing the foreground Palette codec. */
  public final Object fgPaletteLock = new String("fgPalette");

  /** Lock used for accessing the info codec. */
  public final Object infoLock = new String("info");

  /** Lock to signal any image update, for progressive rendering. */
  public final String progressiveLock = new String("progressive");

  /** Lock used for accessing the text codec. */
  public final Object textLock = new String("text");

  /** The mimetype of this document. */
  public String mimetype = null;

  /** The shared dictionary data. */
  protected CachedInputStream sjbzChunk = null;

  /** Number incremented each time the image pixels are updated. */
  protected Number progressiveCount = new Integer(0);

  /**
   * All the codec are stored in a hash table to make adding new codecs
   * easier.
   */
  private HashMap<Object, Codec> codecTable = new HashMap<>();

  private GPixmap fgPixmap = null;

  private ArrayList<IFFEnumeration> chunksToDecode = new ArrayList<>();

  private String waitingForInclude;

  private List<CodecInclude> currentIncludes;

  private JB2Decode jb2ToDecode;

  /** The URL for this page. */
  protected String url = null;

  //~ Methods ----------------------------------------------------------------

  /**
   * Get the background pixmap.
   *
   * @param rect area to render
   * @param subsample rate to subsample
   * @param gamma color correction factor
   * @param retval an old pixmap to reuse rather than allocating more memory
   *
   * @return the pixmap of interest
   */
  public GPixmap getBgPixmap(
    GRect   rect,
    int     subsample,
    double  gamma,
    GPixmap retval)
  {
    GPixmap        pm    = null;
    final DjVuInfo info  = getInfo();
    int            width = (info == null)
      ? 0
      : info.width;
    int height = (info == null)
      ? 0
      : info.height;

    if((width <= 0) || (height <= 0) || (info == null))
    {
      return null;
    }

    double gamma_correction = 1.0D;

    if(gamma > 0.0D)
    {
      gamma_correction = gamma / info.gamma;
    }

    if(gamma_correction < 0.10000000000000001D)
    {
      gamma_correction = 0.10000000000000001D;
    }
    else if(gamma_correction > 10D)
    {
      gamma_correction = 10D;
    }

    final Pixmap bgIWPixmap = getBgPixmap();

    if(bgIWPixmap != null)
    {
      final int w = bgIWPixmap.getWidth();
      final int h = bgIWPixmap.getHeight();

      if((w == 0) || (h == 0) || (width == 0) || (height == 0))
      {
        return null;
      }

      final int red = compute_red(width, height, w, h);

      if((red < 1) || (red > 12))
      {
        return null;
      }

      if(subsample == red)
      {
        pm = bgIWPixmap.getPixmap(1, rect, retval);
      }
      else if(subsample == (2 * red))
      {
        pm = bgIWPixmap.getPixmap(2, rect, retval);
      }
      else if(subsample == (4 * red))
      {
        pm = bgIWPixmap.getPixmap(4, rect, retval);
      }
      else if(subsample == (8 * red))
      {
        pm = bgIWPixmap.getPixmap(8, rect, retval);
      }
      else if((red * 4) == (subsample * 3))
      {
        GRect xrect = new GRect();
        xrect.xmin   = (int)Math.floor(rect.xmin*4D/3D);
        xrect.ymin   = (int)Math.floor(rect.ymin*4D/3D);
        xrect.xmax   = (int)Math.ceil(rect.xmax*4D/3D);
        xrect.ymax   = (int)Math.ceil(rect.ymax*4D/3D);
        final GRect nrect=new GRect(0,0, rect.width(), rect.height());
        if(xrect.xmax > w)
        {
          xrect.xmax = w;
        }

        if(xrect.ymax > h)
        {
          xrect.ymax = h;
        }

        GPixmap ipm = bgIWPixmap.getPixmap(1, xrect, null);
        pm = (retval != null)
          ? retval
          : new GPixmap();
        pm.downsample43(ipm, nrect);
      }
      else
      {
        int po2 = 16;

        while((po2 > 1) && (subsample < (po2 * red)))
        {
          po2 >>= 1;
        }

        final int           inw  = ((w + po2) - 1) / po2;
        final int           inh  = ((h + po2) - 1) / po2;
        final int           outw = ((width + subsample) - 1) / subsample;
        final int           outh = ((height + subsample) - 1) / subsample;
        final GPixmapScaler ps   = createGPixmapScaler(inw, inh, outw, outh);
        ps.setHorzRatio(red * po2, subsample);
        ps.setVertRatio(red * po2, subsample);

        final GRect   xrect = ps.getRequiredRect(rect);
        final GPixmap ipm = bgIWPixmap.getPixmap(po2, xrect, null);
        pm = (retval != null)
          ? retval
          : new GPixmap();
        ps.scale(xrect, ipm, rect, pm);
      }

      if((pm != null) && (gamma_correction != 1.0D))
      {
        pm.applyGammaCorrection(gamma_correction);

        for(int i = 0; i < 9; i++)
        {
          pm.applyGammaCorrection(gamma_correction);
        }
      }

      return pm;
    }
    else
    {
      return null;
    }
  }

  /**
   * Query if this is a compound or photo DjVu page.
   *
   * @return true if color.
   */
  public boolean isColor()
  {
    return (is_legal_compound() || !is_legal_bilevel());
  }

  /**
   * Query the Anno Codec for this page.
   *
   * @return Annotation for this page.
   */
  public Codec getAnno()
  {
    return getCodec(annoLock);
  }

  /**
   * @return the background Pixmap codec for this page.
   */
  public Pixmap getBgPixmap()
  {
    return (Pixmap)getCodec(bgIWPixmapLock);
  }

  /**
   * Query the named Codec for this page.
   *
   * @param nameLock DOCUMENT ME!
   *
   * @return the named Codec for this page.
   */
  public Codec getCodec(final Object nameLock)
  {
    synchronized(nameLock)
    {
      return codecTable.get(nameLock);
    }
  }

  /**
   * Create a new map.  The rectangle of interest and subsample rate are
   * looked up from the old map, and stored in the new map.  A null may be
   * returned if the subsample rate is not legal or there is no valid image.
   *
   * @param segment The bounding rectangle of the subsampled segment.
   * @param subsample The subsample rate.
   * @param retval an old image to fill rather than creating a new image
   *
   * @return The newly created image map.
   */
  public final GMap getMap(
    final GRect segment,
    final int   subsample,
    GMap        retval)
  {
    Number count = progressiveCount;
    retval =
      isColor()
      ? (GMap)getPixmap(
        segment,
        subsample,
        0.0D,
        (retval instanceof GPixmap)
        ? (GPixmap)retval
        : null)
      : (GMap)getBitmap(
        segment,
        subsample);

    if(retval != null)
    {
      retval.properties.put(progressiveLock, count);
      retval.properties.put(
        "rect",
        new GRect(segment));
      retval.properties.put(
        "subsample",
        new Integer(subsample));
    }

    return retval;
  }

  /**
   * Query the foreground Jb2 codec for this page.
   *
   * @return the foreground Jb2 codec for this page.
   */
  public JB2Image getFgJb2()
  {
    // There is no need to synchronize since we won't access data which could be updated.
    return (JB2Image)codecTable.get(fgJb2Lock);
  }

  /**
   * Query the foreground Jb2 Dict codec for this page.
   *
   * @return the foreground Jb2 Dict codec for this page.
   */
  public JB2Dict getFgJb2Dict()
  {
    // There is no need to synchronize since we won't access data which could be updated.
    return (JB2Dict)codecTable.get(fgJb2DictLock);
  }

  /**
   * Query the foreground palette codec for this page.
   *
   * @return foreground palette codec for this page.
   */
  public Palette getFgPalette()
  {
    // There is no need to synchronize since we won't access data which could be updated.
    return (Palette)codecTable.get(fgPaletteLock);
  }

  /**
   * Query the DjVuInfo for this page.
   *
   * @return DjVuInfo for this page.
   */
  public DjVuInfo getInfo()
  {
    // There is no need to synchronize since we won't access data which could be updated.
    return (DjVuInfo)codecTable.get(infoLock);
  }

  /**
   * Query the progressive count.
   *
   * @return the named Codec for this page.
   */
  public int getProgressiveCount()
  {
    return progressiveCount.intValue();
  }

  /**
   * Query the hidden text Codec for this page.
   *
   * @return Hidden text for this page.
   */
  public DjVuText getText()
  {
    // There is no need to synchronize since we won't access data which could be updated.
    return (DjVuText) codecTable.get(textLock);
  }

  /**
   * Compute the subsample rate used.
   *
   * @param w full size width
   * @param h fill size height
   * @param rw reduced width
   * @param rh reduced height
   *
   * @return subsample rate, or 16
   */
  public static int compute_red(
    int w,
    int h,
    int rw,
    int rh)
  {
    for(int red = 1; red < 16; red++)
    {
      if(((((w + red) - 1) / red) == rw) && ((((h + red) - 1) / red) == rh))
      {
        return red;
      }
    }

    return 16;
  }

  /**
   * Called to create an instance of GPixmapScaler
   *
   * @param inw Source image width.
   * @param inh Source image height.
   * @param outw Destination image width.
   * @param outh Destination image height.
   *
   * @return DOCUMENT ME!
   */
  public GPixmapScaler createGPixmapScaler(
    final int inw,
    final int inh,
    final int outw,
    final int outh)
  {
    return new GPixmapScaler(inw, inh, outw, outh);
  }

  /**
   * Query if the specified codec is available.
   *
   * @param nameLock DOCUMENT ME!
   *
   * @return true if the specified codec is available.
   */
  public boolean hasCodec(final Object nameLock)
  {
    return (codecTable.get(nameLock) != null);
  }

  /**
   * Test if this is a photo image.
   *
   * @return true if a photo image
   */
  public boolean is_legal_photo()
  {
    final DjVuInfo info = getInfo();

    if(info == null)
    {
      return false;
    }

    final int width  = info.width;
    final int height = info.height;

    if((width <= 0) || (height <= 0))
    {
      return false;
    }

    return hasCodec(bgIWPixmapLock) && !hasCodec(fgJb2Lock)
    && fgPixmap == null;
  }

  /**
   * Update an image map based on an existing one.  If the existing map is
   * null  or anything has been updated, a new image map will be fetched.
   * Otherwise, the existing image will be returned.  A null may be returned
   * if the subsample  rate is not legal, or no pixels are available.
   *
   * @param map The existing map.
   * @param segment The bounding rectangle of the subsampled segment.
   * @param subsample The subsample rate.
   *
   * @return The newly created image map.
   */
  public final GMap updateMap(
    final GMap  map,
    final GRect segment,
    final int   subsample)
  {
    if(map != null)
    {
      final Object subsampleO = map.properties.get("subsample");

      if(
        (subsampleO instanceof Number)
        && (((Number)subsampleO).intValue() == subsample)
        && progressiveCount.equals(map.properties.get(progressiveLock))
        && segment.equals(map.properties.get("rect")))
      {
        return map;
      }
    }

    return getMap(segment, subsample, null);
  }

  /**
   * Get the pixmap for the desired region.
   *
   * @param rect area of interest
   * @param subsample rate to subsample
   * @param gamma color correction factor
   * @param retval an old image to use instead of creating a new one
   *
   * @return the pixmap of interest
   */
  public GPixmap getPixmap(
    final GRect  rect,
    final int    subsample,
    final double gamma,
    GPixmap      retval)
  {
    if(rect.isEmpty())
    {
      return (retval == null)
      ? (new GPixmap())
      : retval.init(0, 0, null);
    }

    GPixmap bg=getBgPixmap(rect, subsample, gamma, retval);
    if(hasCodec(fgJb2Lock))
    {
      if(bg == null)
      {
          bg=(retval == null)?new GPixmap():retval;
          bg.init(
           rect.height(), 
           rect.width(), GPixel.WHITE);
      }
      if(stencil(bg, rect, subsample, gamma))
      {
        retval=bg;
      }
    }
    else
    {
        retval=bg;
    }
    return retval;
  }

  /**
   * Get a bitmap for the specifed region
   *
   * @param rect area of interest
   * @param subsample subsample rate
   *
   * @return the newly created image
   */
  public GBitmap getBitmap(
    final GRect  rect,
    final int    subsample)
  {
    if(rect.isEmpty())
    {
      return new GBitmap();
    }

    final DjVuInfo info = getInfo();

    if(info != null)
    {
      final int      width  = info.width;
      final int      height = info.height;

      final JB2Image fgJb2 = getFgJb2();

      if(
        (width != 0)
        && (height != 0)
        && (fgJb2 != null)
        && (fgJb2.width == width)
        && (fgJb2.height == height))
      {
        return fgJb2.get_bitmap(rect, subsample);
      }
    }

    return null;
  }

  /**
   * Query if this is a bitonal image.
   *
   * @return true if bitonal
   */
  public boolean is_legal_bilevel()
  {
    final DjVuInfo info = getInfo();

    if(info == null)
    {
      return false;
    }

    final int width  = info.width;
    final int height = info.height;

    if((width <= 0) || (height <= 0))
    {
      return false;
    }

    final JB2Image fgJb2 = getFgJb2();

    if((fgJb2 == null) || (fgJb2.width != width) || (fgJb2.height != height))
    {
      return false;
    }

    return !(hasCodec(bgIWPixmapLock) || fgPixmap != null
    || hasCodec(fgPaletteLock));
  }

  /**
   * Query if this is a compound image.
   *
   * @return true if a compound image
   */
  public boolean is_legal_compound()
  {
    final DjVuInfo info = getInfo();

    if(info == null)
    {
      return false;
    }

    final int width  = info.width;
    final int height = info.height;

    if((width <= 0) || (height <= 0))
    {
      return false;
    }

    final JB2Image fgJb2 = getFgJb2();

    if((fgJb2 == null) || (fgJb2.width != width) || (fgJb2.height != height))
    {
      return false;
    }

    // There is no need to synchronize since we won't access data which could be updated.
    final IWPixmap bgIWPixmap = (IWPixmap)codecTable.get(bgIWPixmapLock);
    int            bgred = 0;

    if(bgIWPixmap != null)
    {
      bgred =
        compute_red(
          width,
          height,
          bgIWPixmap.getWidth(),
          bgIWPixmap.getHeight());
    }

    if((bgred < 1) || (bgred > 12))
    {
      return false;
    }

    int fgred = 0;

    if(fgPixmap != null)
    {
      fgred = compute_red(
          width,
          height,
          fgPixmap.columns(),
          fgPixmap.rows());
    }

    return ((fgred >= 1) && (fgred <= 12));
  }

  /**
   * Create an image by stenciling the foreground onto the background.
   *
   * @param pm the background image to stencil
   * @param rect the area of the page being stenciled
   * @param subsample the subsample rate of the area being stenciled
   * @param gamma the color correction factor
   *
   * @return true if successfull
   */
  public boolean stencil(
    GPixmap pm,
    GRect   rect,
    int     subsample,
    double  gamma)
  {
    final DjVuInfo info = getInfo();

    if(info == null)
    {
      return false;
    }

    final int width  = info.width;
    final int height = info.height;

    if((width <= 0) || (height <= 0))
    {
      return false;
    }

    double gamma_correction = 1.0D;

    if(gamma > 0.0D)
    {
      gamma_correction = gamma / info.gamma;
    }

    if(gamma_correction < 0.10000000000000001D)
    {
      gamma_correction = 0.10000000000000001D;
    }
    else if(gamma_correction > 10D)
    {
      gamma_correction = 10D;
    }

    final JB2Image fgJb2 = getFgJb2();

    if(fgJb2 != null)
    {
      final Palette fgPalette = getFgPalette();

      if(fgPalette != null)
      {
        final int blitCount = fgJb2.get_blit_count();
        if(blitCount != fgPalette.colordata.length)
        {
          GBitmap bm = getBitmap(rect, subsample);
          pm.attenuate(bm);

          return false;
        }

        final int colorsCount = fgPalette.size();
        GPixmap colors =
          new GPixmap().init(
            1,
            colorsCount,
            null);
        final GPixelReference color = colors.createGPixelReference(0);

        for(int i = 0; i < colors.columns(); color.incOffset())
        {
          fgPalette.index_to_color(i++, color);
        }

        colors.applyGammaCorrection(gamma_correction);

        List<List<JB2Blit>> blitsByColor = new ArrayList<>(colorsCount);
        for (int i = 0; i < colorsCount; i++) {
            List<JB2Blit> blits = new ArrayList<>();
            blitsByColor.add(blits);
        }
        for (int pos = 0; pos < blitCount; pos++) {
            final JB2Blit pblit = fgJb2.get_blit(pos);
            if (!fgJb2.intersects(pblit, rect, subsample))
              continue;
            List<JB2Blit> blits = blitsByColor.get(fgPalette.colordata[pos]);
            blits.add(pblit);
        }


        int lastx = 0;
        GRect comprect = new GRect();
        ArrayList<JB2Blit> compset = new ArrayList<>();
        for (int colorIndex = 0; colorIndex < colorsCount; colorIndex++) {
            List<JB2Blit> blits = blitsByColor.get(colorIndex);
            if (blits == null)
                continue;
            color.setOffset(colorIndex);
            comprect.clear();
            compset.clear();
            for (int i = 0; i < blits.size(); i++) {
                JB2Blit pblit = blits.get(i);
                if (lastx < pblit.left) {
                    pm.blit(
                        blit(compset, comprect, rect, subsample),
                        comprect.xmin - rect.xmin,
                        comprect.ymin - rect.ymin,
                        color);
                    comprect.clear();
                    compset.clear();
                }
                lastx = pblit.left;

                final JB2Shape pshape = fgJb2.get_shape(pblit.shapeno);
                final GRect    xrect =
                  new GRect(
                    pblit.left,
                    pblit.bottom,
                    pshape.getGBitmap().columns(),
                    pshape.getGBitmap().rows());
                comprect.recthull(comprect, xrect);
                compset.add(pblit);
            }
            pm.blit(
                blit(compset, comprect, rect, subsample),
                comprect.xmin - rect.xmin,
                comprect.ymin - rect.ymin,
                color);
        }
        return true;
      }


      if(fgPixmap != null)
      {
        GBitmap bm = getBitmap(rect, subsample);

        if((bm != null) && (pm != null))
        {
          int           w   = fgPixmap.columns();
          int           h   = fgPixmap.rows();
          int           red = compute_red(width, height, w, h);

//          if((red < 1) || (red > 12))
          if((red < 1) || (red > 16))
          {
            return false;
          }
//
//          int supersample = (red <= subsample)
//            ? 1
//            : (red / subsample);
//          int wantedred = supersample * subsample;
//
//          if(red == wantedred)
//          {
//            pm.stencil(bm, fgPixmap, supersample, rect, gamma_correction);
//
//            return 1;
//          }
          pm.stencil(bm, fgPixmap, red, subsample, rect, gamma_correction);
          return true;
        }
      }
    }

    return false;
  }

  private GBitmap blit(ArrayList<JB2Blit> compset, GRect comprect, GRect rect, int subsample) {
      if (compset.isEmpty() || comprect.isEmpty())
          return null;
      comprect.xmin /= subsample;
      comprect.ymin /= subsample;
      comprect.xmax   = ((comprect.xmax + subsample) - 1) / subsample;
      comprect.ymax   = ((comprect.ymax + subsample) - 1) / subsample;
      comprect.intersect(comprect, rect);
      if(comprect.isEmpty())
          return null;

      GBitmap bm = new GBitmap();
      bm.init(
        comprect.height(),
        comprect.width(),
        0);
      bm.setGrays(1 + (subsample * subsample));

      final int rxmin = comprect.xmin * subsample;
      final int rymin = comprect.ymin * subsample;

      final JB2Image fgJb2 = getFgJb2();
      for(JB2Blit pblit : compset)
      {
        final JB2Shape pshape = fgJb2.get_shape(pblit.shapeno);
        bm.blit(
          pshape.getGBitmap(),
          pblit.left - rxmin,
          pblit.bottom - rymin,
          subsample);
      }
      return bm;
  }

  /**
   * Called to decode a chunk.
   *
   * @param iff stream being processed
   * @param isInclude true if this is an include file stream
   *
   * @throws IOException if an error occurs
   * @throws IllegalStateException if an error occurs
   */
  protected void decodeChunk(
    final CachedInputStream iff,
    final boolean        isInclude)
    throws IOException
  {
    final String chkid=iff.getName();
      if(chkid.equals("Djbz"))
      {
        addCodecChunk(
          fgJb2DictLock,
          new JB2Dict(),
          iff);

        if(sjbzChunk != null)
        {
          parseSjbz(new CachedInputStream(sjbzChunk));
        }
      }
      else if(chkid.equals("ANTa")||chkid.equals("ANTz"))
      {
        synchronized(annoLock)
        {
          Codec anno = getCodec(annoLock);

          if(anno == null)
          {
            anno = new DjVuAnno();
          }

          addCodecChunk(annoLock, anno, iff);
        }
      }
      else if(!isInclude)
      {
        if(chkid.equals("INFO"))
        {
          throw new IllegalStateException(
            "DjVu Decoder: Corrupted file (Duplicate INFO chunk)");
        }
        else if(chkid.equals("INCL"))
        {
         waitingForInclude = iff.readFullyUTF();
        }
        else if(chkid.equals("FGbz"))
        {
          if(fgPixmap != null)
          {
            throw new IllegalStateException("Duplicate foreground");
          }

          addCodecChunk(
            fgPaletteLock,
            new Palette(),
            iff);
        }
        else if(chkid.equals("TXTa")||chkid.equals("TXTz"))
        {
          addCodecChunk(
            textLock,
            new DjVuText(),
            iff);
        }
        else if(chkid.equals("Sjbz"))
        {
          if(this.sjbzChunk != null)
          {
            throw new IllegalStateException("Duplicate Sjbz chunk");
          }

//          final CachedInputStream sjbzChunk = iff.getCachedInputStream();

          try
          {
            iff.mark(Integer.MAX_VALUE);
            parseSjbz(iff);
          }
          catch(IllegalStateException exp)
          {
//            if(hasCodec(fgJb2DictLock) || (sjbzChunk == null))
            if(hasCodec(fgJb2DictLock))
            {
              throw exp;
            }
            iff.reset();
            this.sjbzChunk = iff;
          }
        }
        else if(chkid.equals("BG44"))
        {
//bcr          if(getInfo().width > 600){verbose("bcr extra wait");try {Thread.sleep(5000L);}catch(final Throwable ignored) {}}
          synchronized(bgIWPixmapLock)
          {
            Codec bgIWPixmap = getCodec(bgIWPixmapLock);

            if(bgIWPixmap == null)
            {
              bgIWPixmap = new IWPixmap();
            }

            addCodecChunk(bgIWPixmapLock, bgIWPixmap, iff);
          }
        }
        else if(chkid.equals("FG44"))
        {
          if(hasCodec(fgPaletteLock) || fgPixmap != null)
          {
            throw new IllegalStateException(
              "DjVu Decoder: Corrupted data (Duplicate foreground layer)");
          }
          IWPixmap fgIWPixmap = new IWPixmap();
          fgIWPixmap.decode(iff);
          fgPixmap = fgIWPixmap.getPixmap();
        }
        else if(chkid.equals("BG2k"))
        {
          if(hasCodec(bgIWPixmapLock))
          {
            throw new IllegalStateException(
              "DjVu Decoder: Corrupted data (Duplicate background layer)");
          }
        }
        else if(chkid.equals("FG2k"))
        {
          if(fgPixmap != null || hasCodec(fgPaletteLock))
          {
            throw new IllegalStateException(
              "DjVu Decoder: Corrupted data (Duplicate foreground layer)");
          }
        }
        else if(chkid.equals("Smmr"))
        {
          if(hasCodec(fgJb2Lock))
          {
            throw new IllegalStateException(
              "DjVu Decoder: Corrupted data (Duplicate background layer)");
          }
        }
        else if(chkid.equals("BGjp"))
        {
          if(hasCodec(bgIWPixmapLock))
          {
            throw new IllegalStateException(
              "DjVu Decoder: Corrupted data (Duplicate background layer)");
          }
          setCodec(bgIWPixmapLock, decodeJPEG(iff));
        }
        else if(chkid.equals("FGjp"))
        {
			if (fgPixmap != null || hasCodec(fgPaletteLock)) {
				throw new IllegalStateException("DjVu Decoder: Corrupted data (Duplicate foreground layer)");
			}
			fgPixmap = decodeJPEG(iff);
        }
      }
  }
  
  private native String btoa(String b64) /*-{
	return btoa(b64);
  }-*/;

  private GPixmap decodeJPEG(CachedInputStream iff) throws IOException {
	final GPixmap result = new GPixmap();

	final Image image = new Image();
	final ImageElement imageElement = image.getElement().cast();
	imageElement.getStyle().setProperty("visibility", "hidden");
	Event.setEventListener(imageElement, new EventListener() {
		@Override
		public void onBrowserEvent(Event event) {
			if (Event.ONLOAD == event.getTypeInt()) {
				final int w = imageElement.getWidth(), h = imageElement.getHeight();
				final Canvas canvas = Canvas.createIfSupported();
				canvas.setWidth(w + "px");
				canvas.setCoordinateSpaceWidth(w);
				canvas.setHeight(h + "px");
				canvas.setCoordinateSpaceHeight(h);
				canvas.getContext2d().drawImage(imageElement, 0, 0);

				result.init(h, w, null);
				//TODO result.setImageData(canvas.getContext2d().getImageData(0, 0, w, h));
			}
		}
	});
	
	StringBuilder data = new StringBuilder();
	int b;
	while ((b = iff.read()) != -1) {
		data.append((char) b);
	}
	String dataURL = "data:image/jpeg;base64," + btoa(data.toString());
	image.setUrl(dataURL);

	return result;
}

/**
   * Called to decode an include chunk.
   *
   * @param pool chunk to be read
   *
   * @throws IOException if an error occurs
   * @throws IllegalStateException if an error occurs
   */
  protected boolean decodeInclude(CachedInputStream pool)
    throws IOException
  {
    if (!pool.isReady())
      return false;
    final IFFEnumeration iff = pool.getIFFChunks();
      if((iff == null)||!iff.hasMoreElements())
      {
        throw new IOException("EOF");
      }
      final CachedInputStream formStream=iff.nextElement();
      final IFFEnumeration formIff=formStream.getIFFChunks();
      if((formIff != null)&&"FORM:DJVI".equals(formStream.getName()))
      {
        chunksToDecode.add(formIff);
        return true;
      }
      else
      {
        throw new IllegalStateException(
          "DejaVu decoder: a DJVI include was expected");
      }
  }

  /**
   * Called to parse jb2 data.
   *
   * @param input stream to parse
   *
   * @throws IOException if an error occurs
   * @throws IllegalStateException if an error occurs
   */
  protected void parseSjbz(final CachedInputStream input)
    throws IOException
  {
    sjbzChunk = null;

    final JB2Image fgJb2 = new JB2Image();

    final DjVuInfo info = getInfo();

    if((info != null) && (info.version < 19))
    {
      fgJb2.reproduce_old_bug = true;
    }

    jb2ToDecode = fgJb2.decodeStart(
      input,
      getFgJb2Dict());

    if(setCodec(fgJb2Lock, fgJb2) != null)
    {
      throw new IllegalStateException(
        "DjVu Decoder: Corrupted data (Duplicate FGxx chunk)");
    }
  }

  /**
   * Called to create a CachedInputStream of the given id.
   * 
   * @param id name of the CachedInputStream to create
   * 
   * @return the newly created CachedInputStream
   * 
   * @throws IOException if an error occurs
   */
  CachedInputStream createCachedInputStream(final String id)
    throws IOException
  {
    return new CachedInputStream().init(Utils.url(url, id));
  }

  /**
   * Set the named Codec for this page.
   *
   * @param nameLock Name of the codec to set.
   * @param codec Named codec for for this page.
   *
   * @return Old codec of this name for this page.
   */
  private Codec setCodec(
    final Object nameLock,
    final Codec  codec)
  {
    Codec        retval;

    synchronized(nameLock)
    {
      retval =
        (codec == null)
        ? codecTable.remove(nameLock)
        : codecTable.put(nameLock, codec);
    }
    if (currentIncludes != null) {
    	CodecInclude include = new CodecInclude(nameLock, codec);
		currentIncludes.add(include);
    }

    if((codec != null)&&codec.isImageData())
    {
      synchronized(progressiveLock)
      {
        final Number count = progressiveCount;
        progressiveCount = new Integer(count.intValue() + 1);
      }
    }
    return retval;
  }

  // Decode the specified codec from the datapool.
  private void addCodecChunk(
    final Object   nameLock,
    final Codec    codec,
    final CachedInputStream pool)
    throws IOException
  {
    if(codec != null)
    {
    	if (jb2ToDecode == null && codec instanceof JB2Decode) {
    		jb2ToDecode = (JB2Decode) codec;
    	} else {
    		codec.decode(pool);
    	}

      final Codec old = setCodec(nameLock, codec);

      if((old != null) && (old != codec))
      {
        throw new IllegalStateException("Duplicate " + nameLock);
      }
    }
  }

	public void decodeStart(CachedInputStream pool) throws IOException {
		synchronized (progressiveLock) {
			if (getProgressiveCount() != 0) {
				throw new IllegalStateException(DjVuPage.class.getName() + " decode already called.");
			}
			progressiveCount = 1;
		}

		final Enumeration<CachedInputStream> iff = pool.getIFFChunks();

		if ((iff == null) || !iff.hasMoreElements()) {
			throw new IOException("EOF");
		}
		CachedInputStream formStream = iff.nextElement();
		IFFEnumeration iffChunks = formStream.getIFFChunks();
		chunksToDecode.add(iffChunks);

		if ("FORM:DJVU".equals(formStream.getName())) {
			mimetype = "image/djvu";
			if (!hasCodec(infoLock)) {

				final CachedInputStream chunk = iffChunks.nextElement();
				if (!"INFO".equals(chunk.getName())) {
					throw new IOException("DjVuDecoder:: Corrupted file (Does not start with INFO chunk)");
				}

				addCodecChunk(infoLock, new DjVuInfo(), chunk);
			}
		} else if ("FORM:PM44".equals(formStream.getName()) || "FORM:BM44".equals(formStream.getName())) {
			mimetype = "image/iw44";
		} else {
			throw new IllegalStateException("DejaVu decoder: a DJVU or IW44 image was expected");
		}
	}

	/**
	 * @return {@code true} if this was the last step
	 */
	public boolean decodeStep() throws IOException {
		if (jb2ToDecode != null) {
			boolean finished = jb2ToDecode.decodeStep();
			if (finished)
				jb2ToDecode = null;
			return false;
		}
		if (waitingForInclude != null) {
			List<CodecInclude> includes = sharedIncludes.get(waitingForInclude);
			if (includes != null) {
				for (CodecInclude include : includes)
					setCodec(include.lock, include.codec);
			} else {
				CachedInputStream includeStream = createCachedInputStream(waitingForInclude);
				if (!decodeInclude(includeStream))
					return false; //waiting for download
				if (currentIncludes == null) {
					currentIncludes = new ArrayList<>();
					sharedIncludes.put(waitingForInclude, currentIncludes);
				}
			}
			waitingForInclude = null;
		}
		ArrayList<IFFEnumeration> ctd = chunksToDecode;
		while (!ctd.isEmpty() && !ctd.get(ctd.size() - 1).hasMoreElements()) {
			ctd.remove(ctd.size() - 1);
			if (ctd.size() == 1)
				currentIncludes = null;
		}
		if (ctd.isEmpty()) {
			if (sjbzChunk != null) {
				parseSjbz(new CachedInputStream(sjbzChunk));
			} else {
				decodeFinish();
				return true;
			}
		}
		CachedInputStream chunk = ctd.get(ctd.size() - 1).nextElement();
		if (mimetype.equals("image/djvu")) {
			boolean isInclude = ctd.size() > 1;
			decodeChunk(chunk, isInclude);
		} else if (mimetype.equals("image/iw44")) {
			if ("PM44".equals(chunk.getName()) || "BM44".equals(chunk.getName())) {
				IWPixmap img44 = (IWPixmap) getCodec(bgIWPixmapLock);
				if (img44 == null) {
					img44 = new IWPixmap();
					img44.decode(chunk);
					final DjVuInfo info = new DjVuInfo();
					info.width = img44.getWidth();
					info.height = img44.getHeight();
					info.dpi = 100;
					setCodec(infoLock, info);
					setCodec(bgIWPixmapLock, img44);
				} else {
					img44.decode(chunk);
				}
			} else if ("ANTa".equals(chunk.getName()) || "ANTz".equals(chunk.getName())) {
				synchronized (annoLock) {
					Codec anno = getCodec(annoLock);

					if (anno == null) {
						anno = new DjVuAnno();
					}
					addCodecChunk(annoLock, anno, chunk);
				}
			}
		}
		return false;
	}

	private void decodeFinish() {
		if (mimetype.equals("image/djvu")) {
			final Pixmap bgIWPixmap = getBgPixmap();
			if (bgIWPixmap instanceof IWPixmap) {
				((IWPixmap) bgIWPixmap).close_codec();
			}
			final DjVuInfo info = getInfo();
			if (info == null) {
				throw new IllegalStateException("DjVu Decoder: Corrupted data (Missing INFO chunk)");
			}
		} else if (mimetype.equals("image/iw44")) {
			if (!hasCodec(infoLock)) {
				throw new IllegalStateException("DjVu Decoder: Corrupted data (Missing IW44 data chunks)");
			}
		}
		setCodec(fgJb2DictLock, null);
	}

	public int getMemoryUsage() {
		int usage = 0;
		Pixmap iwmap = getBgPixmap();
		if (iwmap != null)
			usage += iwmap.getMemoryUsage();
		JB2Dict jb2 = getFgJb2();
		if (jb2 != null)
			usage += jb2.getMemoryUsage();
		Palette palette = getFgPalette();
		if (palette != null)
			usage += palette.getMemoryUsage();
		if (fgPixmap != null)
			usage += fgPixmap.data.byteLength();
		return usage;
	}
}
