import java.util.*;
import org.wangshu.scan.core.Enhance;
/** Independent full-image integral oracle for the bounded-memory production algorithm. */
public final class EnhanceReferenceTests {
    static int clamp(int x){return Math.max(0,Math.min(255,x));}
    static int gray(int p){return ((((p>>>16)&255)*77+((p>>>8)&255)*150+(p&255)*29+128)>>>8);}
    static int[] reference(int[] input,int w,int h,String mode,int brightness,int bias,boolean shadow){
        int[] out=input.clone();long[] sums=new long[(w+1)*(h+1)];for(int y=0;y<h;y++)for(int x=0;x<w;x++)sums[(y+1)*(w+1)+x+1]=gray(input[y*w+x])+sums[y*(w+1)+x+1]+sums[(y+1)*(w+1)+x]-sums[y*(w+1)+x];
        int radius=Math.max(8,Math.min(75,Math.min(w,h)/24));
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int x0=Math.max(0,x-radius),x1=Math.min(w,x+radius+1),y0=Math.max(0,y-radius),y1=Math.min(h,y+radius+1),s=w+1,p=input[y*w+x],g=gray(p);double mean=(sums[y1*s+x1]-sums[y0*s+x1]-sums[y1*s+x0]+sums[y0*s+x0])/(double)((x1-x0)*(y1-y0));if(mode.equals("BW")){int v=g+brightness<mean-Math.max(1,bias)?0:255;out[y*w+x]=0xff000000|v*0x010101;}else{double scale=shadow?Math.min(1.8,250/Math.max(80,mean)):1;int r,green,b;if(mode.equals("GRAY")){r=green=b=clamp((int)Math.round(g*scale)+brightness);}else{r=clamp((int)Math.round(((p>>>16)&255)*scale)+brightness);green=clamp((int)Math.round(((p>>>8)&255)*scale)+brightness);b=clamp((int)Math.round((p&255)*scale)+brightness);}out[y*w+x]=0xff000000|r<<16|green<<8|b;}}return out;
    }
    public static void main(String[] args){Random random=new Random(29837);int cases=0;for(int[] size:new int[][]{{1,1},{1,40},{40,1},{7,4},{19,35},{190,211},{601,480},{1920,1080},{1810,1800}}){int w=size[0],h=size[1];int[] data=new int[w*h];for(int i=0;i<data.length;i++)data[i]=random.nextInt();for(String mode:new String[]{"COLOR","GRAY","BW"})for(boolean shadows:new boolean[]{false,true})for(int brightness:new int[]{-40,0,40}){int bias=cases%34+1;int[] actual=data.clone();Enhance.apply(actual,w,h,mode,brightness,bias,shadows);if(!Arrays.equals(actual,reference(data,w,h,mode,brightness,bias,shadows)))throw new AssertionError("Pixel mismatch "+w+"x"+h+" "+mode+" "+shadows+" "+brightness);cases++;}}System.out.println("PASS "+cases+" independent exact-pixel enhancement comparisons, including clipped borders and local radii");}
}
