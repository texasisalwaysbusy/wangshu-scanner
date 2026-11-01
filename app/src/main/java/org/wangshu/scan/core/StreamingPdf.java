package org.wangshu.scan.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Lossless image PDF 1.4. Flush each page immediately, bounding bitmap memory to one page. */
public final class StreamingPdf implements Closeable {
    private final RandomAccessFile out;
    private final long[] offsets;
    private final int count;
    private int written=0;
    private boolean finished=false;
    public StreamingPdf(File file,int pageCount) throws IOException {
        if(pageCount<1||pageCount>1000)throw new IOException("Invalid page count");
        count=pageCount;offsets=new long[3+3*count];out=new RandomAccessFile(file,"rw");out.setLength(0);
        text("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");object(1);text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        object(2);text("<< /Type /Pages /Count "+count+" /Kids [");for(int i=0;i<count;i++)text((3+3*i)+" 0 R ");text("] >>\nendobj\n");
    }
    private void text(String s) throws IOException {out.write(s.getBytes(StandardCharsets.ISO_8859_1));}
    private void object(int id)throws IOException{offsets[id]=out.getFilePointer();text(id+" 0 obj\n");}
    public void page(File deflated,int width,int height,int channels,int pageWidth,int pageHeight,float margin) throws IOException {
        if(finished||written>=count||width<1||height<1||(channels!=1&&channels!=3))throw new IOException("Invalid PDF page");
        int page=3+3*written,content=page+1,image=page+2;float scale=Math.min((pageWidth-2*margin)/width,(pageHeight-2*margin)/height),w=width*scale,h=height*scale;
        object(page);text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "+pageWidth+" "+pageHeight+"] /Resources << /XObject << /Im0 "+image+" 0 R >> >> /Contents "+content+" 0 R >>\nendobj\n");
        byte[] commands=String.format(Locale.ROOT,"q %.5f 0 0 %.5f %.5f %.5f cm /Im0 Do Q\n",w,h,(pageWidth-w)/2,(pageHeight-h)/2).getBytes(StandardCharsets.US_ASCII);
        object(content);text("<< /Length "+commands.length+" >>\nstream\n");out.write(commands);text("endstream\nendobj\n");
        object(image);text("<< /Type /XObject /Subtype /Image /Width "+width+" /Height "+height+" /ColorSpace /"+(channels==1?"DeviceGray":"DeviceRGB")+" /BitsPerComponent 8 /Filter /FlateDecode /Length "+deflated.length()+" >>\nstream\n");
        try(InputStream in=new FileInputStream(deflated)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}text("\nendstream\nendobj\n");written++;
    }
    public void finish()throws IOException{
        if(written!=count)throw new IOException("Incomplete PDF");long xref=out.getFilePointer();text("xref\n0 "+offsets.length+"\n0000000000 65535 f \n");
        for(int i=1;i<offsets.length;i++)text(String.format(Locale.ROOT,"%010d 00000 n \n",offsets[i]));
        text("trailer\n<< /Size "+offsets.length+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n");out.getFD().sync();finished=true;
    }
    @Override public void close()throws IOException{out.close();}
}
