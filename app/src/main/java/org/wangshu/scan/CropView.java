package org.wangshu.scan;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import org.wangshu.scan.core.Geometry;

/** Four large drag targets plus a precision magnifier. Quad remains in upright image coordinates. */
final class CropView extends View {
    private Bitmap bitmap;
    private double[] quad=Geometry.full();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF image=new RectF();
    private int selected=-1;
    private int activePointer=-1;
    private double[] beforeDrag;
    private final float density;
    CropView(Context c){super(c);density=getResources().getDisplayMetrics().density;setContentDescription("裁切图片：拖动四个角调整边界");setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
    void setImage(Bitmap b,double[] q){cancelDrag(false);image.setEmpty();bitmap=b;quad=q.clone();invalidate();}
    double[] getQuad(){return quad.clone();}
    void setQuad(double[] q){cancelDrag(false);quad=q.clone();invalidate();}
    @Override protected void onDraw(Canvas c){
        c.drawColor(getContext().getColor(R.color.crop_canvas));if(bitmap==null)return;
        float pad=24*density,scale=Math.min((getWidth()-pad*2)/bitmap.getWidth(),(getHeight()-pad*2)/bitmap.getHeight());
        if(scale<=0){image.setEmpty();return;}
        float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
        image.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);c.drawBitmap(bitmap,null,image,paint);
        Path shape=new Path();shape.setFillType(Path.FillType.EVEN_ODD);shape.addRect(image,Path.Direction.CW);
        shape.moveTo(px(0),py(0));for(int i=1;i<4;i++)shape.lineTo(px(i),py(i));shape.close();paint.setColor(0x99000000);c.drawPath(shape,paint);
        Path border=new Path();border.moveTo(px(0),py(0));for(int i=1;i<4;i++)border.lineTo(px(i),py(i));border.close();
        paint.setColor(getContext().getColor(R.color.crop_border));paint.setStrokeWidth(2*density);paint.setStyle(Paint.Style.STROKE);c.drawPath(border,paint);
        paint.setStyle(Paint.Style.FILL);
        for(int i=0;i<4;i++){paint.setColor(Color.WHITE);c.drawCircle(px(i),py(i),11*density,paint);paint.setColor(getContext().getColor(R.color.crop_handle));c.drawCircle(px(i),py(i),7*density,paint);}
        if(selected>=0){
            float r=55*density,cx=getWidth()/2f,cy=65*density;
            c.save();Path clip=new Path();clip.addCircle(cx,cy,r,Path.Direction.CW);c.clipPath(clip);
            Matrix m=new Matrix();m.setTranslate((float)(-quad[selected*2]*bitmap.getWidth()),(float)(-quad[selected*2+1]*bitmap.getHeight()));m.postScale(scale*3,scale*3);m.postTranslate(cx,cy);
            c.drawColor(Color.WHITE);c.drawBitmap(bitmap,m,paint);paint.setColor(0xffe65031);paint.setStrokeWidth(density);c.drawLine(cx-12*density,cy,cx+12*density,cy,paint);c.drawLine(cx,cy-12*density,cx,cy+12*density,paint);c.restore();
            paint.setStyle(Paint.Style.STROKE);paint.setColor(Color.WHITE);paint.setStrokeWidth(2*density);c.drawCircle(cx,cy,r,paint);paint.setStyle(Paint.Style.FILL);
        }
    }
    private float px(int i){return image.left+(float)quad[i*2]*image.width();}
    private float py(int i){return image.top+(float)quad[i*2+1]*image.height();}
    private void updateTouch(MotionEvent e,int index){
        double[] candidate=quad.clone();candidate[2*selected]=Math.max(0,Math.min(1,(e.getX(index)-image.left)/image.width()));candidate[2*selected+1]=Math.max(0,Math.min(1,(e.getY(index)-image.top)/image.height()));
        if(Geometry.valid(candidate))quad=candidate;
    }
    private void cancelDrag(boolean restore){
        if(restore&&beforeDrag!=null)quad=beforeDrag;
        selected=-1;activePointer=-1;beforeDrag=null;
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);invalidate();
    }
    @Override protected void onDetachedFromWindow(){cancelDrag(true);super.onDetachedFromWindow();}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);cancelDrag(true);image.setEmpty();}
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        if(bitmap==null||image.width()==0)return false;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
            cancelDrag(true);
            float best=72*density;selected=-1;
            for(int i=0;i<4;i++){float d=(float)Math.hypot(e.getX()-px(i),e.getY()-py(i));if(d<best){best=d;selected=i;}}
            if(selected<0)return false;activePointer=e.getPointerId(0);beforeDrag=quad.clone();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);invalidate();return true;
        }
        if(selected<0)return false;
        if(e.getActionMasked()==MotionEvent.ACTION_CANCEL||e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN||e.getActionMasked()==MotionEvent.ACTION_POINTER_UP){cancelDrag(true);return true;}
        int index=e.findPointerIndex(activePointer);if(index<0){cancelDrag(true);return true;}
        if(selected>=0&&e.getActionMasked()==MotionEvent.ACTION_MOVE){
            updateTouch(e,index);invalidate();return true;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_UP){updateTouch(e,index);cancelDrag(false);performClick();return true;}
        return selected>=0;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
