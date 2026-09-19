package io.github.yannickfan67.unhccb;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lightweight pure-Android decoder for screenshots / near-front-on photos.
 * It intentionally avoids shipping Microsoft binaries or OpenCV. The Python tool
 * in /tools remains the more robust perspective-corrected research decoder.
 */
public final class TagImageDecoder {
    private TagImageDecoder() {}

    public static final class ImageResult {
        public final HccbCodec.DecodeResult decoded;
        public final RectF frame;
        public final int quarterTurns;
        public final boolean mirrored;
        public ImageResult(HccbCodec.DecodeResult d, RectF frame, int q, boolean m) {
            this.decoded=d; this.frame=frame; this.quarterTurns=q; this.mirrored=m;
        }
    }

    private static final float TRI_H=60f, SEP=11f, BLACK_SIDE=24f, BLACK_TOP=18f, BLACK_BOTTOM=34f;
    private static final float BASE=2f*TRI_H/(float)Math.sqrt(3), DX=BASE/2f;
    private static final float BLACK_W=2f*BLACK_SIDE + BASE + 9f*DX;
    private static final float BLACK_H=BLACK_TOP+BLACK_BOTTOM+5f*TRI_H+6f*SEP;

    public static ImageResult decode(Bitmap input) {
        Bitmap bmp=scaleDown(input,1400);
        List<RectF> frames=new ArrayList<>();
        frames.add(new RectF(0,0,bmp.getWidth()-1,bmp.getHeight()-1));
        RectF byColor=findFrameFromColour(bmp);
        if(byColor!=null) frames.add(byColor);
        List<ImageResult> hits=new ArrayList<>();
        for(RectF f:frames){
            for(boolean mirror:new boolean[]{false,true}){
                for(int q=0;q<4;q++){
                    try{
                        int[] cells=sampleCells(bmp,f,q,mirror);
                        HccbCodec.DecodeResult d=HccbCodec.decodeResilient(cells,2);
                        if(d.strong()) hits.add(new ImageResult(d,f,q,mirror));
                    }catch(Exception ignored){}
                }
            }
        }
        if(hits.isEmpty()) throw new IllegalArgumentException("找不到有效 Microsoft Tag。請把 Tag 裁得更緊、保持正面再試。 ");
        hits.sort(Comparator.comparingInt(a -> a.decoded.correctedCells));
        return hits.get(0);
    }

    private static Bitmap scaleDown(Bitmap src,int maxDim){
        int w=src.getWidth(),h=src.getHeight(),m=Math.max(w,h);
        if(m<=maxDim) return src;
        float s=maxDim/(float)m;
        return Bitmap.createScaledBitmap(src,Math.max(1,Math.round(w*s)),Math.max(1,Math.round(h*s)),true);
    }

    private static RectF findFrameFromColour(Bitmap b){
        int w=b.getWidth(),h=b.getHeight();
        int minX=w,minY=h,maxX=-1,maxY=-1,count=0;
        int step=Math.max(1,Math.max(w,h)/900);
        for(int y=0;y<h;y+=step){
            for(int x=0;x<w;x+=step){
                int c=b.getPixel(x,y), r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
                int mx=Math.max(r,Math.max(g,bl)), mn=Math.min(r,Math.min(g,bl));
                if(mx>150 && mx-mn>110){
                    minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);count++;
                }
            }
        }
        if(count<30 || maxX<=minX || maxY<=minY) return null;
        float cw=maxX-minX, ch=maxY-minY;
        // Recover the black-frame bounds from the coloured triangle lattice extents.
        float left=minX - cw*0.063f, right=maxX + cw*0.063f;
        float top=minY - ch*0.084f, bottom=maxY + ch*0.131f;
        left=Math.max(0,left);top=Math.max(0,top);right=Math.min(w-1,right);bottom=Math.min(h-1,bottom);
        RectF r=new RectF(left,top,right,bottom);
        float ratio=r.width()/Math.max(1f,r.height());
        if(ratio<0.65f || ratio>1.55f) return null;
        return r;
    }

    private static int[] sampleCells(Bitmap b,RectF f,int q,boolean mirror){
        float[][] rgb=new float[50][3];
        for(int i=0;i<50;i++){
            int row=i/10,col=i%10;
            float x=BLACK_SIDE + col*DX + BASE/2f;
            float triTop=BLACK_TOP + row*(SEP+TRI_H)+SEP;
            float y=triTop + (((col&1)==0) ? TRI_H/3f : 2f*TRI_H/3f);
            float u=x/BLACK_W, v=y/BLACK_H;
            if(mirror) u=1f-u;
            float tu=u,tv=v;
            switch(q){
                case 1: tu=1f-v; tv=u; break;
                case 2: tu=1f-u; tv=1f-v; break;
                case 3: tu=v; tv=1f-u; break;
            }
            float px=f.left+tu*f.width(), py=f.top+tv*f.height();
            rgb[i]=medianPatch(b,px,py,Math.max(2,Math.round(Math.min(f.width()/BLACK_W,f.height()/BLACK_H)*4f)));
        }
        float[][] refs={rgb[46],rgb[47],rgb[48],rgb[49]};
        float minSep=Float.MAX_VALUE;
        for(int i=0;i<4;i++) for(int j=i+1;j<4;j++) minSep=Math.min(minSep,dist(refs[i],refs[j]));
        if(minSep<35f) throw new IllegalArgumentException("weak colour calibration");
        int[] cells=new int[50];
        for(int i=0;i<50;i++){
            int best=0;float bd=Float.MAX_VALUE;
            for(int s=0;s<4;s++){float d=dist(rgb[i],refs[s]);if(d<bd){bd=d;best=s;}}
            cells[i]=best;
        }
        return cells;
    }

    private static float[] medianPatch(Bitmap b,float fx,float fy,int radius){
        int cx=Math.round(fx),cy=Math.round(fy),w=b.getWidth(),h=b.getHeight();
        ArrayList<Integer> rs=new ArrayList<>(),gs=new ArrayList<>(),bs=new ArrayList<>();
        for(int y=Math.max(0,cy-radius);y<=Math.min(h-1,cy+radius);y++){
            for(int x=Math.max(0,cx-radius);x<=Math.min(w-1,cx+radius);x++){
                int c=b.getPixel(x,y);rs.add(Color.red(c));gs.add(Color.green(c));bs.add(Color.blue(c));
            }
        }
        rs.sort(Integer::compareTo);gs.sort(Integer::compareTo);bs.sort(Integer::compareTo);
        if(rs.isEmpty()) throw new IllegalArgumentException("sample outside image");
        int m=rs.size()/2;
        return new float[]{rs.get(m),gs.get(m),bs.get(m)};
    }
    private static float dist(float[] a,float[] b){
        float x=a[0]-b[0],y=a[1]-b[1],z=a[2]-b[2];return (float)Math.sqrt(x*x+y*y+z*z);
    }
}
