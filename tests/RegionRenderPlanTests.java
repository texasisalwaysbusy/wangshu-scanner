import org.wangshu.scan.core.Geometry;
import org.wangshu.scan.core.RegionRenderPlan;
import java.util.*;

/** Independent fixed-coordinate planning checks; no Android rendering acceptance. */
public final class RegionRenderPlanTests {
    static int count;
    static void check(boolean yes,String label){if(!yes)throw new AssertionError(label);count++;System.out.println("PASS "+label);}
    static void equal(double[] actual,double[] expected,String label){check(Arrays.equals(actual,expected),label);}
    static void rejects(Runnable operation,String label){boolean rejected=false;try{operation.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,label);}
    static RegionRenderPlan full(int w,int h,int orientation,int rotation,int edge){return RegionRenderPlan.create(w,h,orientation,Geometry.full(),rotation,edge);}
    static void tiles(RegionRenderPlan plan,String label){long area=0;boolean valid=true;for(int n=0;n<plan.tileCount;n++){
        RegionRenderPlan.Tile t=plan.tile(n);area+=(long)(t.right-t.left)*(t.bottom-t.top);
        valid&=t.left>=plan.left&&t.top>=plan.top&&t.right<=plan.right&&t.bottom<=plan.bottom&&t.right>t.left&&t.bottom>t.top;
        valid&=t.decodeLeft<=t.left&&t.decodeTop<=t.top&&t.decodeRight>=t.right&&t.decodeBottom>=t.bottom&&t.decodeLeft>=0&&t.decodeTop>=0&&t.decodeRight<=plan.rawWidth&&t.decodeBottom<=plan.rawHeight;
        valid&=t.decodeRight-t.decodeLeft<=1028&&t.decodeBottom-t.decodeTop<=1028&&t.decodedPixels()<=1028L*1028;
        if(n%plan.columns>0){RegionRenderPlan.Tile previous=plan.tile(n-1);valid&=previous.right==t.left&&previous.top==t.top;}
        if(n>=plan.columns){RegionRenderPlan.Tile previous=plan.tile(n-plan.columns);valid&=previous.bottom==t.top&&previous.left==t.left;}
    }check(valid&&area==(long)(plan.right-plan.left)*(plan.bottom-plan.top),label);}
    public static void main(String[] args){
        RegionRenderPlan p=full(7000,2800,1,0,4000);check(p.width==4000&&p.height==1600,"7000 full source selects exact4000 target, not3500");
        p=full(7000,2800,1,0,2800);check(p.width==2800&&p.height==1120,"balanced2800 target is computed from full source");
        p=full(7000,2800,1,0,1800);check(p.width==1800&&p.height==720,"compact1800 target is computed from full source");
        double[] roi={5000./7000,700./2800,6000./7000,700./2800,6000./7000,2100./2800,5000./7000,2100./2800};
        RegionRenderPlan local=RegionRenderPlan.create(7000,2800,1,roi,0,4000);check(local.width==1000&&local.height==1400,"small source ROI keeps its1000x1400 native detail");
        check(local.left>=4999&&local.left<=5000&&local.top==700&&local.right==6000&&local.bottom==2100,"ROI decode bounds are local raw pixels, not entire7000 image");tiles(local,"local ROI cores cover once with <=1028 decode bounds");
        RegionRenderPlan rotated=RegionRenderPlan.create(7000,2800,1,roi,1,4000);check(rotated.width==1400&&rotated.height==1000&&rotated.outputBytes()==local.outputBytes(),"rotation changes target orientation without a third full bitmap");
        double[] skew={5100./7000,800./2800,5850./7000,860./2800,5900./7000,1980./2800,5050./7000,2040./2800};RegionRenderPlan perspective=RegionRenderPlan.create(7000,2800,1,skew,0,4000);check(perspective.width==852&&perspective.height==1241,"fixed oblique crop has independent852x1241 expected dimensions");tiles(perspective,"perspective bounding tiles include halo without duplicate core area");
        double[][] rawCorners={{0,0,30,0,30,20,0,20},{30,0,0,0,0,20,30,20},{30,20,0,20,0,0,30,0},{0,20,30,20,30,0,0,0},{0,0,0,20,30,20,30,0},{0,20,0,0,30,0,30,20},{30,20,30,0,0,0,0,20},{30,0,30,20,0,20,0,0}};
        double[][] point={{7,11},{23,11},{23,9},{7,9},{11,7},{11,13},{19,13},{19,7}};
        for(int exif=1;exif<=8;exif++){
            RegionRenderPlan e=full(30,20,exif,0,4000);equal(e.rawQuad(),rawCorners[exif-1],"EXIF"+exif+" upright corners map to independently fixed raw edges");equal(RegionRenderPlan.uprightToRaw(7,11,30,20,exif),point[exif-1],"EXIF"+exif+" interior inverse point");
            for(int turn=0;turn<4;turn++){RegionRenderPlan r=full(30,20,exif,turn,4000);boolean swap=(exif>=5)^((turn&1)==1);check(r.width==(swap?20:30)&&r.height==(swap?30:20),"EXIF"+exif+" + quarter turn"+turn+" size stays native, no upsample");}
        }
        double[][] targets={{0,0,30,0,30,20,0,20},{20,0,20,30,0,30,0,0},{30,20,0,20,0,0,30,0},{0,30,0,0,20,0,20,30}};
        for(int turn=0;turn<4;turn++)equal(full(30,20,1,turn,4000).destinationQuad(),targets[turn],"rotation"+turn+" is directly included in output mapping");
        tiles(full(7000,7000,1,0,4000),"49MP input never decodes a whole RGBA source; 49 bounded tiles cover it");
        RegionRenderPlan square=full(7000,7000,1,0,4000);check(square.tileCount==49&&square.outputBytes()==64_000_000,"16MP output bytes are64MB independent of49MP source");
        RegionRenderPlan strip=full(65535,2,1,0,4000);tiles(strip,"extreme wide strip tiles remain bounded");check(strip.tileCount==64,"65535x2 strip requires64 tiles, not excessive allocations");
        tiles(full(2,65535,1,0,4000),"extreme tall strip tiles remain bounded");
        RegionRenderPlan extreme=full(65535,2288,1,0,4000);check(extreme.tileCount==192,"near150MP wide source fits256-tile bound");
        int maxTiles=0;for(int w=2;w<=65535;w++){int h=(int)Math.min(65535,150_000_000L/w);if(h<2)continue;maxTiles=Math.max(maxTiles,((w+1023)/1024)*((h+1023)/1024));}check(maxTiles<=256,"all allowed integer aspect ratios up to150MP and65535 edges fit256 tiles; observed maximum="+maxTiles);
        check(square.estimatedWorkingBytes(false,5*RegionRenderPlan.MIB,false)<90*RegionRenderPlan.MIB,"COLOR estimate counts output + one tile + encoded source reserve");
        check(square.estimatedWorkingBytes(true,5*RegionRenderPlan.MIB,false)<130*RegionRenderPlan.MIB,"enhancement peaks below130MiB without rotation/full-source duplicate");
        check(RegionRenderPlan.workingBudget(256*RegionRenderPlan.MIB,32*RegionRenderPlan.MIB)==160*RegionRenderPlan.MIB,"working estimate capped160MiB even with large heap");
        check(RegionRenderPlan.workingBudget(128*RegionRenderPlan.MIB,120*RegionRenderPlan.MIB)==0,"low actual Java headroom yields explicit zero budget");
        check(square.estimatedWorkingBytes(true,5*RegionRenderPlan.MIB,false)>RegionRenderPlan.workingBudget(128*RegionRenderPlan.MIB,16*RegionRenderPlan.MIB),"16MP BW cannot silently pass a small heap budget");
        double[] returned=local.rawQuad();returned[0]=-1;check(local.rawQuad()[0]>=0,"plan raw corners are immutable to getter caller");
        rejects(()->full(7000,2800,1,0,4001),"caller cannot silently increase4000 output bound");rejects(()->full(20000,10000,1,0,4000),"source150MP bound is enforced");rejects(()->full(65536,2,1,0,4000),"source edge bound is enforced");rejects(()->RegionRenderPlan.create(30,20,1,null,0,4000),"null crop rejected explicitly");rejects(()->local.tile(-1),"negative tile index rejected");rejects(()->local.tile(local.tileCount),"past-end tile index rejected");
        System.out.println("PASS "+count+" pure planning checks; Android region decode/filter/seams/PDF/memory behavior NOT_RUN");
    }
}
