package ai.clawphones.agent.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * ImageView that renders a detection bounding box on top of the displayed frame.
 */
public final class BoundingBoxImageView extends AppCompatImageView {

    private final Paint mBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Nullable private RectF mBoundingBox;

    public BoundingBoxImageView(@NonNull Context context) {
        super(context);
        init();
    }

    public BoundingBoxImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BoundingBoxImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mBoxPaint.setStyle(Paint.Style.STROKE);
        mBoxPaint.setStrokeWidth(6f);
        mBoxPaint.setColor(0xFFFF3B30);
    }

    public void setBoundingBox(@Nullable RectF box) {
        if (box == null) {
            mBoundingBox = null;
        } else {
            mBoundingBox = new RectF(box);
        }
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        Drawable drawable = getDrawable();
        RectF source = mBoundingBox;
        if (drawable == null || source == null) return;

        int intrinsicWidth = drawable.getIntrinsicWidth();
        int intrinsicHeight = drawable.getIntrinsicHeight();
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return;

        RectF normalized = normalizeBoundingBox(source, intrinsicWidth, intrinsicHeight);
        if (normalized == null) return;

        float[] values = new float[9];
        Matrix imageMatrix = getImageMatrix();
        imageMatrix.getValues(values);

        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        float scaleX = values[Matrix.MSCALE_X];
        float scaleY = values[Matrix.MSCALE_Y];

        float renderedWidth = intrinsicWidth * scaleX;
        float renderedHeight = intrinsicHeight * scaleY;
        if (renderedWidth <= 0 || renderedHeight <= 0) return;

        float left = getPaddingLeft() + transX + normalized.left * renderedWidth;
        float top = getPaddingTop() + transY + normalized.top * renderedHeight;
        float right = getPaddingLeft() + transX + normalized.right * renderedWidth;
        float bottom = getPaddingTop() + transY + normalized.bottom * renderedHeight;

        canvas.drawRect(left, top, right, bottom, mBoxPaint);
    }

    @Nullable
    private RectF normalizeBoundingBox(@NonNull RectF box, int imageWidth, int imageHeight) {
        boolean alreadyNormalized = box.right <= 1.01f && box.bottom <= 1.01f;
        RectF normalized;
        if (alreadyNormalized) {
            normalized = new RectF(box);
        } else {
            normalized = new RectF(
                box.left / imageWidth,
                box.top / imageHeight,
                box.right / imageWidth,
                box.bottom / imageHeight
            );
        }

        normalized.left = clamp(normalized.left);
        normalized.top = clamp(normalized.top);
        normalized.right = clamp(normalized.right);
        normalized.bottom = clamp(normalized.bottom);
        if (normalized.right <= normalized.left || normalized.bottom <= normalized.top) {
            return null;
        }
        return normalized;
    }

    private float clamp(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }
}
