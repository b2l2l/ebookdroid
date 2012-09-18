package org.ebookdroid.core;

import org.ebookdroid.common.bitmaps.BitmapManager;
import org.ebookdroid.common.bitmaps.Bitmaps;
import org.ebookdroid.common.bitmaps.IBitmapRef;
import org.ebookdroid.common.bitmaps.RawBitmap;
import org.ebookdroid.common.cache.DocumentCacheFile.PageInfo;
import org.ebookdroid.common.settings.AppSettings;
import org.ebookdroid.common.settings.books.BookSettings;
import org.ebookdroid.common.settings.definitions.AppPreferences;
import org.ebookdroid.core.codec.CodecPage;
import org.ebookdroid.core.models.DecodingProgressModel;
import org.ebookdroid.ui.viewer.IViewController;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.emdev.common.log.LogContext;
import org.emdev.utils.MatrixUtils;

public class PageTreeNode implements DecodeService.DecodeCallback {

    private static final LogContext LCTX = Page.LCTX;

    final Page page;
    final PageTreeNode parent;
    final int id;
    final PageTreeLevel level;
    final String shortId;
    final String fullId;

    final AtomicBoolean decodingNow = new AtomicBoolean();
    final BitmapHolder holder = new BitmapHolder();

    final RectF pageSliceBounds;

    float bitmapZoom = 1;
    private RectF autoCropping = null;
    private RectF manualCropping = null;

    public RectF getCropping() {
        return manualCropping != null ? manualCropping : autoCropping;
    }

    public void setInitialCropping(final PageInfo pi) {
        if (id != 0) {
            return;
        }

        if (pi != null) {
            autoCropping = pi.autoCropping != null ? new RectF(pi.autoCropping) : null;
            manualCropping = pi.manualCropping != null ? new RectF(pi.manualCropping) : null;
        } else {
            autoCropping = null;
            manualCropping = null;
        }

        updateAspectRatio();
    }

    public void setAutoCropping(final RectF r, final boolean commit) {
        autoCropping = r;
        if (commit && id == 0) {
            page.base.getDocumentModel().updateAutoCropping(page, r);
            updateAspectRatio();
        }
    }

    public void setManualCropping(final RectF r, final boolean commit) {
        manualCropping = r;
        if (commit && id == 0) {
            page.base.getDocumentModel().updateManualCropping(page, r);
            updateAspectRatio();
        }
    }

    public void updateAspectRatio() {
        if (page.base.getBookSettings().cropPages) {
            final RectF cropping = getCropping();
            if (cropping != null) {
                final float pageWidth = page.cpi.width * cropping.width();
                final float pageHeight = page.cpi.height * cropping.height();
                page.setAspectRatio(pageWidth, pageHeight);
            }
        }
    }

    PageTreeNode(final Page page) {
        assert page != null;

        this.page = page;
        this.parent = null;
        this.id = 0;
        this.level = PageTreeLevel.ROOT;
        this.shortId = page.index.viewIndex + ":0";
        this.fullId = page.index + ":0";
        this.pageSliceBounds = page.type.getInitialRect();
        this.autoCropping = null;
    }

    PageTreeNode(final Page page, final PageTreeNode parent, final int id, final RectF localPageSliceBounds) {
        assert id != 0;
        assert page != null;
        assert parent != null;

        this.page = page;
        this.parent = parent;
        this.id = id;
        this.level = parent.level.next;
        this.shortId = page.index.viewIndex + ":" + id;
        this.fullId = page.index + ":" + id;
        this.pageSliceBounds = evaluatePageSliceBounds(localPageSliceBounds, parent);

        evaluateCroppedPageSliceBounds();
    }

    @Override
    protected void finalize() throws Throwable {
        holder.recycle(null);
    }

    public boolean recycle(final List<Bitmaps> bitmapsToRecycle) {
        stopDecodingThisNode("node recycling");
        return holder.recycle(bitmapsToRecycle);
    }

    protected void decodePageTreeNode(final List<PageTreeNode> nodesToDecode, final ViewState viewState) {
        if (this.decodingNow.compareAndSet(false, true)) {
            bitmapZoom = viewState.zoom;
            nodesToDecode.add(this);
        }
    }

    void stopDecodingThisNode(final String reason) {
        if (this.decodingNow.compareAndSet(true, false)) {
            final DecodingProgressModel dpm = page.base.getDecodingProgressModel();
            if (dpm != null) {
                dpm.decrease();
            }
            if (reason != null) {
                final DecodeService ds = page.base.getDecodeService();
                if (ds != null) {
                    ds.stopDecoding(this, reason);
                }
            }
        }
    }

    @Override
    public void decodeComplete(final CodecPage codecPage, final IBitmapRef bitmap, final Rect bitmapBounds,
            final RectF croppedPageBounds) {

        try {
            if (bitmap == null || bitmapBounds == null) {
                page.base.getActivity().runOnUiThread(new Runnable() {

                    @Override
                    public void run() {
                        stopDecodingThisNode(null);
                    }
                });
                return;
            }

            final BookSettings bs = page.base.getBookSettings();
            if (bs != null) {
                final boolean correctContrast = bs.contrast != AppPreferences.CONTRAST.defValue;
                final boolean correctExposure = bs.exposure != AppPreferences.EXPOSURE.defValue;

                if (correctContrast || correctExposure || bs.autoLevels) {
                    final RawBitmap raw = new RawBitmap(bitmap, bitmapBounds);
                    if (correctContrast) {
                        raw.contrast(bs.contrast);
                    }
                    if (correctExposure) {
                        raw.exposure(bs.exposure - AppPreferences.EXPOSURE.defValue);
                    }
                    if (bs.autoLevels) {
                        raw.autoLevels();
                    }
                    bitmap.setPixels(raw);
                }
            }

            final Bitmaps bitmaps = holder.reuse(fullId, bitmap, bitmapBounds);

            final Runnable r = new Runnable() {

                @Override
                public void run() {
                    // long t0 = System.currentTimeMillis();
                    holder.setBitmap(bitmaps);
                    stopDecodingThisNode(null);

                    final IViewController dc = page.base.getDocumentController();
                    if (dc instanceof AbstractViewController) {
                        EventPool.newEventChildLoaded((AbstractViewController) dc, PageTreeNode.this, bitmapBounds)
                                .process();
                    }
                }
            };

            page.base.getActivity().runOnUiThread(r);
        } catch (final OutOfMemoryError ex) {
            LCTX.e("No memory: ", ex);
            BitmapManager.clear("PageTreeNode OutOfMemoryError: ");
            page.base.getActivity().runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    stopDecodingThisNode(null);
                }
            });
        } finally {
            BitmapManager.release(bitmap);
        }
    }

    public RectF getTargetRect(final RectF pageBounds) {
        return Page.getTargetRect(page.type, pageBounds, pageSliceBounds);
    }

    public static RectF evaluatePageSliceBounds(final RectF localPageSliceBounds, final PageTreeNode parent) {
        final Matrix tmpMatrix = MatrixUtils.get();

        tmpMatrix.postScale(parent.pageSliceBounds.width(), parent.pageSliceBounds.height());
        tmpMatrix.postTranslate(parent.pageSliceBounds.left, parent.pageSliceBounds.top);
        final RectF sliceBounds = new RectF();
        tmpMatrix.mapRect(sliceBounds, localPageSliceBounds);
        return sliceBounds;
    }

    public void evaluateCroppedPageSliceBounds() {
        if (parent == null) {
            return;
        }

        if (parent.getCropping() == null) {
            parent.evaluateCroppedPageSliceBounds();
        }

        autoCropping = evaluateCroppedPageSliceBounds(parent.autoCropping, this.pageSliceBounds);
        manualCropping = evaluateCroppedPageSliceBounds(parent.manualCropping, this.pageSliceBounds);
    }

    public static RectF evaluateCroppedPageSliceBounds(final RectF crop, final RectF slice) {
        if (crop == null) {
            return null;
        }

        final Matrix tmpMatrix = MatrixUtils.get();

        tmpMatrix.postScale(crop.width(), crop.height());
        tmpMatrix.postTranslate(crop.left, crop.top);

        final RectF sliceBounds = new RectF();
        tmpMatrix.mapRect(sliceBounds, slice);
        return sliceBounds;
    }

    @Override
    public int hashCode() {
        return (page == null) ? 0 : page.index.viewIndex;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof PageTreeNode) {
            final PageTreeNode that = (PageTreeNode) obj;
            if (this.page == null) {
                return that.page == null;
            }
            return this.page.index.viewIndex == that.page.index.viewIndex
                    && this.pageSliceBounds.equals(that.pageSliceBounds);
        }

        return false;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder("PageTreeNode");
        buf.append("[");

        buf.append("id").append("=").append(page.index.viewIndex).append(":").append(id);
        buf.append(", ");
        buf.append("rect").append("=").append(this.pageSliceBounds);
        buf.append(", ");
        buf.append("hasBitmap").append("=").append(holder.hasBitmaps());

        buf.append("]");
        return buf.toString();
    }

    class BitmapHolder {

        final AtomicReference<Bitmaps> ref = new AtomicReference<Bitmaps>();

        public boolean drawBitmap(final Canvas canvas, final PagePaint paint, final PointF viewBase,
                final RectF targetRect, final RectF clipRect) {
            final Bitmaps bitmaps = ref.get();
            return bitmaps != null ? bitmaps.draw(canvas, paint, viewBase, targetRect, clipRect) : false;
        }

        public Bitmaps reuse(final String nodeId, final IBitmapRef bitmap, final Rect bitmapBounds) {
            final BookSettings bs = page.base.getBookSettings();
            final AppSettings app = AppSettings.current();
            final boolean invert = bs != null ? bs.nightMode : app.nightMode;
            if (app.textureReuseEnabled) {
                final Bitmaps bitmaps = ref.get();
                if (bitmaps != null) {
                    if (bitmaps.reuse(nodeId, bitmap, bitmapBounds, invert)) {
                        return bitmaps;
                    }
                }
            }
            return new Bitmaps(nodeId, bitmap, bitmapBounds, invert);
        }

        public boolean hasBitmaps() {
            final Bitmaps bitmaps = ref.get();
            return bitmaps != null ? bitmaps.hasBitmaps() : false;
        }

        public boolean recycle(final List<Bitmaps> bitmapsToRecycle) {
            final Bitmaps bitmaps = ref.getAndSet(null);
            if (bitmaps != null) {
                if (bitmapsToRecycle != null) {
                    bitmapsToRecycle.add(bitmaps);
                } else {
                    BitmapManager.release(Arrays.asList(bitmaps));
                }
                return true;
            }
            return false;
        }

        public void setBitmap(final Bitmaps bitmaps) {
            if (bitmaps == null) {
                return;
            }
            final Bitmaps oldBitmaps = ref.getAndSet(bitmaps);
            if (oldBitmaps != null && oldBitmaps != bitmaps) {
                BitmapManager.release(Arrays.asList(oldBitmaps));
            }
        }
    }
}
