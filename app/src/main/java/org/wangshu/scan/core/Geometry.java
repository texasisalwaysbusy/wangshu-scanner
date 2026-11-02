package org.wangshu.scan.core;

/** Perspective and quad validation, independent of the Android runtime. */
public final class Geometry {
    private Geometry() {}
    public static double[] full() { return new double[]{0,0,1,0,1,1,0,1}; }
    public static boolean valid(double[] q) {
        if (q == null || q.length != 8) return false;
        for (double v:q) if (!Double.isFinite(v) || v<0 || v>1) return false;
        double area=0;
        for (int i=0;i<4;i++) {
            int j=(i+1)%4,k=(i+2)%4;
            double cross=(q[2*j]-q[2*i])*(q[2*k+1]-q[2*j+1])-(q[2*j+1]-q[2*i+1])*(q[2*k]-q[2*j]);
            if (cross<=0.00001) return false;
            area+=q[2*i]*q[2*j+1]-q[2*j]*q[2*i+1];
        }
        // Permit useful small regions (e.g. a 1000×1000 crop of a 10000×10000
        // image) while retaining a conservative normalized-area floor. Image
        // pixel dimensions are not known here; callers must still validate
        // their render plan and cannot infer real detail from this predicate.
        return area/2>0.001;
    }
    /** 8 coefficients mapping destination rectangle pixels into source quad pixels. */
    public static double[] inverse(double[] q,int sw,int sh,int dw,int dh) {
        if (!valid(q) || Math.min(Math.min(sw,sh),Math.min(dw,dh))<2) throw new IllegalArgumentException("Invalid crop");
        double[][] a=new double[8][9];
        double[] d={0,0,dw-1,0,dw-1,dh-1,0,dh-1};
        for(int i=0;i<4;i++) {
            double x=d[2*i],y=d[2*i+1],u=q[2*i]*(sw-1),v=q[2*i+1]*(sh-1);
            a[2*i]=new double[]{x,y,1,0,0,0,-u*x,-u*y,u};
            a[2*i+1]=new double[]{0,0,0,x,y,1,-v*x,-v*y,v};
        }
        for(int c=0;c<8;c++) {
            int pivot=c;
            for(int r=c+1;r<8;r++) if(Math.abs(a[r][c])>Math.abs(a[pivot][c])) pivot=r;
            if(Math.abs(a[pivot][c])<1e-12) throw new IllegalArgumentException("Degenerate crop");
            double[] t=a[c];a[c]=a[pivot];a[pivot]=t;
            double f=a[c][c];for(int j=c;j<9;j++)a[c][j]/=f;
            for(int r=0;r<8;r++) if(r!=c) { f=a[r][c];for(int j=c;j<9;j++)a[r][j]-=f*a[c][j]; }
        }
        double[] result=new double[8];for(int i=0;i<8;i++)result[i]=a[i][8];return result;
    }
    public static double[] map(double[] m,double x,double y) {
        double z=m[6]*x+m[7]*y+1;
        return new double[]{(m[0]*x+m[1]*y+m[2])/z,(m[3]*x+m[4]*y+m[5])/z};
    }
    public static int[] outputSize(double[] q,int w,int h,int maxEdge) {
        if(!valid(q))throw new IllegalArgumentException("Invalid crop");
        double top=Math.hypot((q[2]-q[0])*w,(q[3]-q[1])*h),bottom=Math.hypot((q[4]-q[6])*w,(q[5]-q[7])*h);
        double left=Math.hypot((q[6]-q[0])*w,(q[7]-q[1])*h),right=Math.hypot((q[4]-q[2])*w,(q[5]-q[3])*h);
        double ow=Math.max(top,bottom),oh=Math.max(left,right),scale=Math.min(1,maxEdge/Math.max(ow,oh));
        return new int[]{Math.max(2,(int)Math.round(ow*scale)),Math.max(2,(int)Math.round(oh*scale))};
    }
}
