package org.wangshu.scan.core;

/** Conservative local enhancement: no erosion, connected-component removal or AI deletion. */
public final class Enhance {
    private Enhance() {}
    public static int gray(int p) { return (((p>>16)&255)*77+((p>>8)&255)*150+(p&255)*29+128)>>8; }
    private static int clamp(int v) { return Math.max(0,Math.min(255,v)); }
    public static void apply(int[] pixels,int w,int h,String mode,int brightness,int bias,boolean shadows) {
        if(w<1||h<1||pixels.length!=(long)w*h)throw new IllegalArgumentException("Image dimensions");
        if(!mode.equals("COLOR")&&!mode.equals("GRAY")&&!mode.equals("BW"))throw new IllegalArgumentException("Mode");
        boolean local=mode.equals("BW")||shadows;
        int radius=Math.max(8,Math.min(75,Math.min(w,h)/24));
        // Retain only original gray rows in the moving window. A full-image
        // integral array used 96 MB for a 12 MP page before any bitmap storage.
        int rows=Math.min(h,2*radius+1);
        byte[] ring=local?new byte[w*rows]:null;
        int[] columns=local?new int[w]:null;
        if(local)for(int y=0;y<Math.min(h,radius+1);y++)for(int x=0;x<w;x++){
            int value=gray(pixels[y*w+x]);ring[y*w+x]=(byte)value;columns[x]+=value;
        }
        for(int y=0;y<h;y++) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("已取消图像处理");
            int sum=0;if(local)for(int x=0;x<Math.min(w,radius+1);x++)sum+=columns[x];
            int verticalCount=Math.min(h,y+radius+1)-Math.max(0,y-radius);
            for(int x=0;x<w;x++) {
            int i=y*w+x,p=pixels[i],g=gray(p),r=(p>>16)&255,b=p&255,green=(p>>8)&255;
            double mean=255;
            if(local){
                int horizontalCount=Math.min(w,x+radius+1)-Math.max(0,x-radius);
                mean=sum/(double)(horizontalCount*verticalCount);
            }
            if(mode.equals("BW")) {
                // Lower bias retains lighter strokes; a gray/color fallback always remains available.
                int v=g+brightness<mean-Math.max(1,bias)?0:255;
                pixels[i]=0xff000000|(v<<16)|(v<<8)|v;
            } else {
                double scale=shadows?Math.min(1.8,250/Math.max(80,mean)):1;
                if(mode.equals("GRAY")){int v=clamp((int)Math.round(g*scale)+brightness);r=v;green=v;b=v;}
                else {r=clamp((int)Math.round(r*scale)+brightness);green=clamp((int)Math.round(green*scale)+brightness);b=clamp((int)Math.round(b*scale)+brightness);}
                pixels[i]=0xff000000|(r<<16)|(green<<8)|b;
            }
            if(local){if(x-radius>=0)sum-=columns[x-radius];if(x+radius+1<w)sum+=columns[x+radius+1];}
            }
            if(local){
                int remove=y-radius,add=y+radius+1;
                if(remove>=0){int offset=(remove%rows)*w;for(int x=0;x<w;x++)columns[x]-=ring[offset+x]&255;}
                if(add<h){int offset=(add%rows)*w;for(int x=0;x<w;x++){int value=gray(pixels[add*w+x]);ring[offset+x]=(byte)value;columns[x]+=value;}}
            }
        }
    }
}
