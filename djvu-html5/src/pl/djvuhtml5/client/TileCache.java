package pl.djvuhtml5.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.canvas.dom.client.ImageData;
import com.google.gwt.dom.client.CanvasElement;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.ImageElement;
import com.google.gwt.typedarrays.shared.Uint8Array;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.EventListener;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RootPanel;
import com.lizardtech.djvu.DjVuInfo;
import com.lizardtech.djvu.DjVuPage;
import com.lizardtech.djvu.GMap;
import com.lizardtech.djvu.GRect;

public class TileCache {

	public final static int MAX_SUBSAMPLE = 12;

	private static final int PREFETCH_AGE = 500;

	private final Djvu_html5 app;

	public final int tileSize;

	private final int tileCacheSize;

	private final PageCache pageCache;

	private final HashMap<TileInfo, CachedItem> cache = new HashMap<>();

	/** All tiles of max subsample are stored here and will never be thrown away */
	private final HashMap<TileInfo, CachedItem> smallCache = new HashMap<>();

	private ArrayList<TileCacheListener> listeners = new ArrayList<>();

	private final CanvasElement missingTileImage;

	private CanvasElement bufferCanvas;
	private ImageData bufferImageData;
	private GMap bufferGMap;

	private final GRect tempRect = new GRect();
	private final TileInfo tempTI = new TileInfo();

	private int lastPageNum = -1, lastSubsample;
	private final GRect lastRange = new GRect();

	public TileCache(Djvu_html5 app) {
		this.app = app;
		this.pageCache = app.getPageCache();
		this.tileCacheSize = DjvuContext.getTileCacheSize();
		this.tileSize = DjvuContext.getTileSize();

		missingTileImage = prepareMissingTileImage();

		bufferCanvas = new CachedItem(tileSize, tileSize).image;
	}

	private String getBlankImageUrl() {
		Element element = new Label().getElement().cast();
		element.addClassName("blankImage");
		RootPanel.get().getElement().appendChild(element);
		try {
			String url = Djvu_html5.getComputedStyleProperty(element, "background-image");
			url = url.replaceAll("^url\\(['\"]?(.*)['\"]\\)$", "$1");
			return url;
		} finally {
			RootPanel.get().getElement().removeChild(element);
		}
	}

	private CanvasElement prepareMissingTileImage() {
		CanvasElement canvas = new CachedItem(tileSize, tileSize).image;
		Context2d context2d = canvas.getContext2d();
		context2d.setFillStyle("white");
		context2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
		Image image = new Image();
		final ImageElement imageElement = image.getElement().cast();
		imageElement.getStyle().setProperty("visibility", "hidden");
		Event.setEventListener(imageElement, new EventListener() {
			
			@Override
			public void onBrowserEvent(Event event) {
				if (Event.ONLOAD == event.getTypeInt()) {
					missingTileImage.getContext2d().drawImage(imageElement, 0, 0);
					RootPanel.get().getElement().removeChild(imageElement);
				}
			}
		});
		RootPanel.get().getElement().appendChild(imageElement);
		image.setUrl(getBlankImageUrl());
		return canvas;
	}

	public static int toSubsample(double zoom) {
		int subsample = (int) Math.floor(1 / zoom);
		subsample = Math.max(1, Math.min(MAX_SUBSAMPLE, subsample));
		return subsample;
	}

	public static double toZoom(int subsample) {
		double zoom = 1.0 / subsample;
		return zoom;
	}

	public CanvasElement[][] getTileImages(int pageNum, int subsample, GRect range, CanvasElement[][] reuse) {
		CanvasElement[][] result = reuse;
		int w = range.width() + 1, h = range.height() + 1;
		if (reuse == null || reuse.length != h || reuse[0].length != w) {
			result = new CanvasElement[h][w];
		}

		if (pageNum != lastPageNum || subsample != lastSubsample || !lastRange.equals(range)) {
			lastPageNum = pageNum;
			lastSubsample = subsample;
			lastRange.clear();
			lastRange.recthull(lastRange, range);
			app.startProcessing();
		}

		tempTI.page = pageNum;
		tempTI.subsample = subsample;
		for (int y = range.ymin; y <= range.ymax; y++)
			for (int x = range.xmin; x <= range.xmax; x++)
				result[y - range.ymin][x - range.xmin] = getTileImage(tempTI.setXY(x, y));

		return result;
	}

	private CanvasElement getTileImage(TileInfo tileInfo) {
		CachedItem cachedItem = getItem(tileInfo);
		if (cachedItem == null) {
			DjVuInfo pageInfo = pageCache.getPage(tileInfo.page).getInfo();

			// fill with rescaled other tiles
			ArrayList<TileInfo> fetched = new ArrayList<>();
			tileInfo.getPageRect(tempRect, tileSize, pageInfo);
			GRect tempRect2 = new GRect();
			for (Map<TileInfo, CachedItem> map : Arrays.asList(smallCache, cache)) {
				for (Entry<TileInfo, CachedItem> entry : map.entrySet()) {
					TileInfo ti = entry.getKey();
					if (ti.page == tileInfo.page && entry.getValue().isFetched) {
						ti.getPageRect(tempRect2, tileSize, pageInfo);
						if (tempRect2.intersect(tempRect2, tempRect))
							fetched.add(ti);
					}
				}
			}
			if (fetched.isEmpty())
				return missingTileImage;

			Collections.sort(fetched, new Comparator<TileInfo>() {
				@Override
				public int compare(TileInfo ti1, TileInfo ti2) {
					return ti2.subsample - ti1.subsample;
				}
			});
			tileInfo.getScreenRect(tempRect, tileSize, pageInfo);
			cachedItem = new CachedItem(tempRect.width(), tempRect.height());
			putItem(tileInfo, cachedItem);
			Context2d context = cachedItem.image.getContext2d();

			tileInfo.getScreenRect(tempRect, tileSize, pageInfo);
			double zoom = toZoom(tileInfo.subsample);
			for (TileInfo ti : fetched) {
				context.save();
				double scale = zoom / toZoom(ti.subsample);
				ti.getScreenRect(tempRect2, tileSize, pageInfo);
				context.translate(-tempRect.xmin, -tempRect.ymin);
				context.scale(scale, scale);
				context.translate(tempRect2.xmin, tempRect2.ymin);
				context.drawImage(getItem(ti).image, 0, 0);
				context.restore();
			}
		}
		cachedItem.lastUsed = System.currentTimeMillis();
		return cachedItem.image;
	}

	public void addTileCacheListener(TileCacheListener listener) {
		listeners.add(listener);
	}

	private void putItem(TileInfo tileInfo, CachedItem cachedItem) {
		if (tileInfo.subsample == MAX_SUBSAMPLE) {
			smallCache.put(new TileInfo(tileInfo), cachedItem);
		} else {
			removeStaleItems();
			cache.put(new TileInfo(tileInfo), cachedItem);
		}
	}

	private CachedItem getItem(TileInfo tileInfo) {
		if (tileInfo.subsample == MAX_SUBSAMPLE)
			return smallCache.get(tileInfo);
		return cache.get(tileInfo);
	}

	private void removeStaleItems() {
		if (cache.size() < tileCacheSize)
			return;

		ArrayList<Entry<TileInfo, CachedItem>> cacheEntries = new ArrayList<>(cache.entrySet());
		Collections.sort(cacheEntries, new Comparator<Entry<TileInfo, CachedItem>>() {
			@Override
			public int compare(Entry<TileInfo, CachedItem> e1, Entry<TileInfo, CachedItem> e2) {
				long d = e1.getValue().lastUsed - e2.getValue().lastUsed;
				return d > 0 ? 1 : d < 0 ? -1 : 0;
			}
		});
		for (int i = 0; i < tileCacheSize / 4; i++) {
			cache.remove(cacheEntries.get(i).getKey());
		}
	}

	boolean prefetchPreviews(boolean all) {
		if (lastPageNum < 0)
			return false;
		tempTI.subsample = MAX_SUBSAMPLE;
		for (int i = 0; i < (all ? pageCache.getPageCount() * 2 : 1); i++) {
			int index = lastPageNum + (i % 2 == 0 ? -1 : 1) * (i / 2);
			if (index < 0 || index >= pageCache.getPageCount())
				continue;
			DjVuPage page = pageCache.getPage(index);
			if (page == null)
				continue;
			tempTI.page = index;
			DjVuInfo info = page.getInfo();
			int w = (info.width + MAX_SUBSAMPLE - 1) / MAX_SUBSAMPLE;
			int h = (info.height + MAX_SUBSAMPLE - 1) / MAX_SUBSAMPLE;
			for (int x = 0; x * tileSize < w; x++) {
				for (int y = 0; y * tileSize < h; y++) {
					if (prepareItem(tempTI.setXY(x, y), page, false))
						return true;
				}
			}
		}
		return false;
	}

	boolean prefetchCurrentView(int pageDelta) {
		int pageNum = lastPageNum + pageDelta;
		if (pageNum < 0 || pageNum >= pageCache.getPageCount())
			return false;
		final DjVuPage page = pageCache.getPage(pageNum);
		if (page == null)
			return false;

		tempTI.page = pageNum;
		tempTI.subsample = lastSubsample;
		for (int y = lastRange.ymin; y <= lastRange.ymax; y++) {
			for (int x = lastRange.xmin; x <= lastRange.xmax; x++) {
				boolean isPrefetch = pageNum != lastPageNum;
				if (prepareItem(tempTI.setXY(x, y), page, isPrefetch))
					return true;
			}
		}
		return false;
	}

	boolean prefetchAdjacent(int pageDelta) {
		int pageNum = lastPageNum + pageDelta;
		if (pageNum < 0 || pageNum >= pageCache.getPageCount())
			return false;
		final DjVuPage page = pageCache.getPage(pageNum);
		if (page == null)
			return false;

		tempTI.page = pageNum;
		tempTI.subsample = lastSubsample;
		final DjVuInfo pageInfo = page.getInfo();
		final int maxX = (int) Math.ceil(1.0 * pageInfo.width / lastSubsample / tileSize) - 1;
		final int maxY = (int) Math.ceil(1.0 * pageInfo.height / lastSubsample / tileSize) - 1;
		final int dx = (lastRange.width() + 1) / 2, dy = (lastRange.height() + 1) / 2;
		for (int d = 1; d <= dx; d++) {
			int x = lastRange.xmax + d;
			for (int y = lastRange.ymin; y <= lastRange.ymax + Math.min(d, dy); y++) {
				if (x >= 0 && x <= maxX && y >= 0 && y <= maxY && prepareItem(tempTI.setXY(x, y), page, true))
					return true;
			}
			x = lastRange.ymin - d;
			for (int y = lastRange.ymin - Math.min(d, dy); y <= lastRange.ymax; y++) {
				if (x >= 0 && x <= maxX && y >= 0 && y <= maxY && prepareItem(tempTI.setXY(x, y), page, true))
					return true;
			}
		}
		for (int d = 1; d <= dy; d++) {
			int y = lastRange.ymax + d;
			for (int x = lastRange.xmin; x <= lastRange.xmax + Math.min(d, dx); x++) {
				if (x >= 0 && x <= maxX && y >= 0 && y <= maxY && prepareItem(tempTI.setXY(x, y), page, true))
					return true;
			}
			y = lastRange.ymin - d;
			for (int x = lastRange.xmin - Math.min(d, dx); x <= lastRange.xmax; x++) {
				if (x >= 0 && x <= maxX && y >= 0 && y <= maxY && prepareItem(tempTI.setXY(x, y), page, true))
					return true;
			}
		}
		return false;
	}

	/**
	 * @return {@code false} iff the item has already been prepared and this method did nothing 
	 */
	private boolean prepareItem(final TileInfo tileInfo, DjVuPage page, boolean isPrefetch) {
		tileInfo.getScreenRect(tempRect, tileSize, page.getInfo());
		if (tempRect.isEmpty())
			return false;
		CachedItem cachedItem = getItem(tileInfo);
		if (cachedItem == null) {
			cachedItem = new CachedItem(tempRect.width(), tempRect.height());
			putItem(tileInfo, cachedItem);
		}
		if (cachedItem.isFetched)
			return false;
	
		bufferGMap = page.getMap(tempRect, tileInfo.subsample, bufferGMap);
		if (bufferGMap != null) {
			if (bufferImageData == null || bufferImageData.getWidth() != bufferGMap.getDataWidth()
					|| bufferImageData.getHeight() != bufferGMap.getDataHeight()) {
				bufferImageData = bufferCanvas.getContext2d()
						.createImageData(bufferGMap.getDataWidth(), bufferGMap.getDataHeight());
			}
			Uint8Array imageArray = bufferImageData.getData().cast();
			imageArray.set(bufferGMap.getImageData());
			bufferCanvas.getContext2d().putImageData(bufferImageData, -bufferGMap.getBorder(), 0);

			Context2d c = cachedItem.image.getContext2d();
			c.setFillStyle("white");
			c.fillRect(0, 0, tileSize, tileSize);
			c.drawImage(bufferCanvas, 0, 0);
		}
		cachedItem.isFetched = true;
		cachedItem.lastUsed = System.currentTimeMillis() - (isPrefetch ? PREFETCH_AGE : 0);
		if (!isPrefetch) {
			TileInfo ti = new TileInfo(tileInfo);
			for (TileCacheListener listener : listeners)
				listener.tileAvailable(ti);
			app.interruptProcessing();
		}
		return true;
	}

	public static final class TileInfo {
		public int page;
		public int subsample;
		public int x;
		public int y;

		public TileInfo(int page, int subsample, int x, int y) {
			this.page = page;
			this.subsample = subsample;
			this.x = x;
			this.y = y;
		}

		public TileInfo(TileInfo toCopy) {
			this(toCopy.page, toCopy.subsample, toCopy.x, toCopy.y);
		}

		public TileInfo() {
			// nothing to do
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + page;
			result = prime * result + x;
			result = prime * result + y;
			result = prime * result + subsample;
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			TileInfo other = (TileInfo) obj;
			if (page != other.page)
				return false;
			if (x != other.x)
				return false;
			if (y != other.y)
				return false;
			if (subsample != other.subsample)
				return false;
			return true;
		}

		void getScreenRect(GRect rect, int tileSize, DjVuInfo info) {
			int pw = (info.width + subsample - 1) / subsample, ph = (info.height + subsample - 1) / subsample;
			getRect(rect, tileSize, pw, ph);
		}

		void getPageRect(GRect rect, int tileSize, DjVuInfo info) {
			getRect(rect, tileSize * subsample, info.width, info.height);
		}

		private void getRect(GRect rect, int tileSize, int pw, int ph) {
			rect.xmin = Math.min(x * tileSize, pw);
			rect.xmax = Math.min((x + 1) * tileSize, pw);
			rect.ymin = Math.max(ph - (y + 1) * tileSize, 0);
			rect.ymax = Math.max(ph - y * tileSize, 0);
			assert rect.xmin <= rect.xmax && rect.ymin <= rect.ymax;
		}

		private TileInfo setXY(int x, int y) {
			this.x = x;
			this.y = y;
			return this;
		}
	}

	public static interface TileCacheListener {
		void tileAvailable(TileInfo tileInfo);
	}

	private static final class CachedItem {
		public final CanvasElement image;
		public long lastUsed;
		public boolean isFetched;

		public CachedItem(int width, int height) {
			Canvas canvas = Canvas.createIfSupported();
			canvas.setWidth(width + "px");
			canvas.setCoordinateSpaceWidth(width);
			canvas.setHeight(height + "px");
			canvas.setCoordinateSpaceHeight(height);
			image = canvas.getCanvasElement();
		}
	}
}
