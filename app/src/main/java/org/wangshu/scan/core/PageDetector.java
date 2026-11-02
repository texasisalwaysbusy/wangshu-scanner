package org.wangshu.scan.core;


/** Optional bright-paper suggestion, deliberately returns full image when confidence is poor. */
public final class PageDetector {
    private PageDetector() {}
    public static double[] detect(int[] p,int w,int h) {
        if(w<4||h<4||(long)w*h!=p.length)throw new IllegalArgumentException("Image dimensions");
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("已取消边界分析");
        int[] hist=new int[256];for(int v:p)hist[Enhance.gray(v)]++;
        long sum=0;for(int i=0;i<256;i++)sum+=(long)i*hist[i];
        long bgSum=0;int bg=0,threshold=160;double max=-1;
        for(int t=0;t<255;t++) {
            bg+=hist[t];bgSum+=(long)t*hist[t];if(bg==0||bg==p.length)continue;
            double diff=bgSum/(double)bg-(sum-bgSum)/(double)(p.length-bg);
            double between=(double)bg*(p.length-bg)*diff*diff;
            if(between>max){max=between;threshold=t;}
        }
        threshold=Math.max(120,Math.min(235,threshold+8));
        Candidate best=component(p,w,h,threshold);
        // A second sizeable bright object makes the intended page ambiguous,
        // even when it is much smaller than the brightest object.
        if(best.size<p.length*0.18||best.size>p.length*0.96||best.second>p.length*.05)return Geometry.full();
        double[] q=quad(best.corners,w,h);
        for(double v:q)if(v<.012||v>.988)return Geometry.full();
        if(!Geometry.valid(q)||!supportedEdges(p,w,h,q))return Geometry.full();

        // A hard illumination boundary can itself look like four supported
        // edges. Check a lower threshold at the observed frame-background
        // percentile: if a substantially larger region is connected to that
        // lit patch, do not silently crop to it. The user can choose corners.
        int[] frame=new int[256];int frameN=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(x==0||y==0||x==w-1||y==h-1){frame[Enhance.gray(p[y*w+x])]++;frameN++;}
        int total=0,background=0;
        for(;background<255;background++){total+=frame[background];if(total>=frameN*.75)break;}
        // A heavy shadow can leave real paper only slightly brighter than the
        // frame, below the old midpoint threshold. Any substantially larger
        // connected region at a conservative background-relative threshold
        // makes the bright patch ambiguous, even if it reaches the frame or its
        // outer edges are too weak to certify. Fall back to manual corners.
        int lower=background;
        if(lower<threshold-4){
            Candidate outer=component(p,w,h,lower);
            if(outer.size>best.size*1.15)return Geometry.full();
        }
        return q;
    }
    private static final class Candidate {int size,second;int[] corners;}
    private static double[] quad(int[] corners,int w,int h){
        double[] q=new double[8];for(int k=0;k<4;k++){q[2*k]=(corners[k]%w)/(double)(w-1);q[2*k+1]=(corners[k]/w)/(double)(h-1);}return q;
    }
    private static Candidate component(int[] p,int w,int h,int threshold){
        boolean[] seen=new boolean[p.length];int[] queue=new int[p.length];Candidate best=new Candidate();
        for(int seed=0;seed<p.length;seed++){
            if((seed&1023)==0&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("已取消边界分析");
            if(seen[seed]||Enhance.gray(p[seed])<=threshold)continue;
            int head=0,tail=1;queue[0]=seed;seen[seed]=true;
            int tl=seed,tr=seed,br=seed,bl=seed;double mins=Double.MAX_VALUE,maxs=-1,mind=Double.MAX_VALUE,maxd=-Double.MAX_VALUE;
            while(head<tail){
                if((head&1023)==0&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("已取消边界分析");
                int i=queue[head++],x=i%w,y=i/w;double sumxy=x/(double)w+y/(double)h,diff=x/(double)w-y/(double)h;
                if(sumxy<mins){mins=sumxy;tl=i;}if(sumxy>maxs){maxs=sumxy;br=i;}if(diff>maxd){maxd=diff;tr=i;}if(diff<mind){mind=diff;bl=i;}
                if(x>0&&!seen[i-1]&&Enhance.gray(p[i-1])>threshold){seen[i-1]=true;queue[tail++]=i-1;}
                if(x+1<w&&!seen[i+1]&&Enhance.gray(p[i+1])>threshold){seen[i+1]=true;queue[tail++]=i+1;}
                if(y>0&&!seen[i-w]&&Enhance.gray(p[i-w])>threshold){seen[i-w]=true;queue[tail++]=i-w;}
                if(y+1<h&&!seen[i+w]&&Enhance.gray(p[i+w])>threshold){seen[i+w]=true;queue[tail++]=i+w;}
            }
            if(tail>best.size){best.second=best.size;best.size=tail;best.corners=new int[]{tl,tr,br,bl};}else if(tail>best.second)best.second=tail;
        }
        return best;
    }
    /** A brightness threshold inside soft shadow is not a visible paper edge.
     * Require most samples along every suggested side to cross a real light/dark
     * boundary. Rounded bright objects and internal shadow cuts fall back to full.
     */
    private static boolean supportedEdges(int[] pixels,int width,int height,double[] quad){
        double distance=Math.max(2,Math.min(width,height)*.0125);
        for(int side=0;side<4;side++){
            int next=(side+1)%4;double x0=quad[2*side]*(width-1),y0=quad[2*side+1]*(height-1),dx=quad[2*next]*(width-1)-x0,dy=quad[2*next+1]*(height-1)-y0,length=Math.hypot(dx,dy);
            if(length<4)return false;double nx=-dy/length*distance,ny=dx/length*distance;int supported=0;
            for(int sample=0;sample<32;sample++){
                double fraction=(sample+1)/33.;double x=x0+dx*fraction,y=y0+dy*fraction;
                int insideX=(int)Math.round(x+nx),insideY=(int)Math.round(y+ny),outsideX=(int)Math.round(x-nx),outsideY=(int)Math.round(y-ny);
                if(insideX<0||insideX>=width||outsideX<0||outsideX>=width||insideY<0||insideY>=height||outsideY<0||outsideY>=height)return false;
                if(Enhance.gray(pixels[insideY*width+insideX])-Enhance.gray(pixels[outsideY*width+outsideX])>=20)supported++;
            }
            if(supported<20)return false;
        }
        return true;
    }

}
