package org.wangshu.scan.core;

/** Raw-image region plan. No Android classes, decoded source bitmap, hidden sampling or file writes. */
public final class RegionRenderPlan {
    public static final int MAX_OUTPUT_EDGE=4000,TILE_EDGE=1024,HALO=2,MAX_TILES=256,MAX_SOURCE_EDGE=65535;
    public static final long MAX_SOURCE_PIXELS=150_000_000L,MAX_OUTPUT_PIXELS=16_000_000L;
    public static final long SMALL_FALLBACK_PIXELS=4_000_000L,MIB=1024L*1024L,MAX_WORKING_BYTES=160*MIB;
    public final int rawWidth,rawHeight,orientation,rotation,uprightWidth,uprightHeight,width,height;
    public final int left,top,right,bottom,columns,rows,tileCount;
    private final double[] rawQuad,destinationQuad;

    private RegionRenderPlan(int rw,int rh,int exif,double[] q,int turn,int maxEdge){
        if(rw<2||rh<2||rw>MAX_SOURCE_EDGE||rh>MAX_SOURCE_EDGE||(long)rw*rh>MAX_SOURCE_PIXELS)throw new IllegalArgumentException("原图尺寸超过安全处理范围");
        if(maxEdge<2||maxEdge>MAX_OUTPUT_EDGE)throw new IllegalArgumentException("输出最长边须为2至4000像素");
        if(!Geometry.valid(q))throw new IllegalArgumentException("裁切四角无效");
        rawWidth=rw;rawHeight=rh;orientation=exif>=1&&exif<=8?exif:1;rotation=Math.floorMod(turn,4);
        boolean swap=orientation>=5;uprightWidth=swap?rh:rw;uprightHeight=swap?rw:rh;
        int[] rectified=Geometry.outputSize(q,uprightWidth,uprightHeight,maxEdge);
        int w=rectified[0],h=rectified[1];width=(rotation&1)==0?w:h;height=(rotation&1)==0?h:w;
        if((long)width*height>MAX_OUTPUT_PIXELS)throw new IllegalArgumentException("输出超过1600万像素安全上限");
        rawQuad=new double[8];double minX=rw,minY=rh,maxX=0,maxY=0;
        for(int i=0;i<4;i++){double[] raw=uprightToRaw(q[2*i]*uprightWidth,q[2*i+1]*uprightHeight,rw,rh,orientation);rawQuad[2*i]=raw[0];rawQuad[2*i+1]=raw[1];minX=Math.min(minX,raw[0]);minY=Math.min(minY,raw[1]);maxX=Math.max(maxX,raw[0]);maxY=Math.max(maxY,raw[1]);}
        left=Math.max(0,(int)Math.floor(minX));top=Math.max(0,(int)Math.floor(minY));right=Math.min(rw,(int)Math.ceil(maxX));bottom=Math.min(rh,(int)Math.ceil(maxY));
        if(right<=left||bottom<=top)throw new IllegalArgumentException("裁切区域为空");
        columns=(right-left+TILE_EDGE-1)/TILE_EDGE;rows=(bottom-top+TILE_EDGE-1)/TILE_EDGE;tileCount=columns*rows;
        if(tileCount>MAX_TILES)throw new IllegalArgumentException("裁切区域需要过多瓦片，请缩小选区；原图保留");
        destinationQuad=new double[8];double[] corners={0,0,w,0,w,h,0,h};
        for(int i=0;i<4;i++){double x=corners[2*i],y=corners[2*i+1];switch(rotation){case 1:destinationQuad[2*i]=h-y;destinationQuad[2*i+1]=x;break;case 2:destinationQuad[2*i]=w-x;destinationQuad[2*i+1]=h-y;break;case 3:destinationQuad[2*i]=y;destinationQuad[2*i+1]=w-x;break;default:destinationQuad[2*i]=x;destinationQuad[2*i+1]=y;}}
    }
    public static RegionRenderPlan create(int rawWidth,int rawHeight,int exif,double[] quad,int rotation,int maxEdge){return new RegionRenderPlan(rawWidth,rawHeight,exif,quad==null?null:quad.clone(),rotation,maxEdge);}
    public double[] rawQuad(){return rawQuad.clone();}
    public double[] destinationQuad(){return destinationQuad.clone();}
    /** Pixel-edge coordinates, not (width-1) pixel-center indices. */
    public static double[] uprightToRaw(double u,double v,int w,int h,int exif){switch(exif){case 2:return new double[]{w-u,v};case 3:return new double[]{w-u,h-v};case 4:return new double[]{u,h-v};case 5:return new double[]{v,u};case 6:return new double[]{v,h-u};case 7:return new double[]{w-v,h-u};case 8:return new double[]{w-v,u};default:return new double[]{u,v};}}
    public static final class Tile {
        public final int left,top,right,bottom,decodeLeft,decodeTop,decodeRight,decodeBottom;
        private Tile(int l,int t,int r,int b,int w,int h){left=l;top=t;right=r;bottom=b;decodeLeft=Math.max(0,l-HALO);decodeTop=Math.max(0,t-HALO);decodeRight=Math.min(w,r+HALO);decodeBottom=Math.min(h,b+HALO);}
        public long decodedPixels(){return (long)(decodeRight-decodeLeft)*(decodeBottom-decodeTop);}
    }
    public Tile tile(int index){if(index<0||index>=tileCount)throw new IllegalArgumentException("瓦片编号无效");int l=left+(index%columns)*TILE_EDGE,t=top+(index/columns)*TILE_EDGE;return new Tile(l,t,Math.min(right,l+TILE_EDGE),Math.min(bottom,t+TILE_EDGE),rawWidth,rawHeight);}
    public long outputBytes(){return (long)width*height*4;}
    /** Decoder is recycled before enhancement; rotation is already part of the destination map. */
    public long estimatedWorkingBytes(boolean enhance,long encodedBytes,boolean wholeFallback){
        if(encodedBytes<0||encodedBytes>Long.MAX_VALUE-2_000_000_000L)throw new IllegalArgumentException("Invalid source length");
        long sourcePixels=wholeFallback?(long)rawWidth*rawHeight:(long)(TILE_EDGE+2*HALO)*(TILE_EDGE+2*HALO);
        long decodePeak=outputBytes()+sourcePixels*4+encodedBytes+8*MIB;
        int radius=Math.max(8,Math.min(75,Math.min(width,height)/24));
        long enhancePeak=enhance?outputBytes()*2+(long)Math.min(height,2*radius+1)*width+(long)width*4:outputBytes();
        return Math.max(decodePeak,enhancePeak);
    }
    public static long workingBudget(long heapMax,long heapUsed){if(heapMax<=0||heapUsed<0)return 0;long reserved=Math.min(48*MIB,heapMax/3);return Math.max(0,Math.min(MAX_WORKING_BYTES,Math.min(heapMax-reserved,heapMax-heapUsed-16*MIB)));}
}
