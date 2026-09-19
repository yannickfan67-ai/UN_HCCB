package io.github.yannickfan67.unhccb;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

public final class TagRenderer {
    private TagRenderer() {}

    public static final int[] PALETTE = {
            Color.rgb(255,255,0),
            Color.rgb(255,0,255),
            Color.rgb(0,0,0),
            Color.rgb(0,255,255)
    };

    public static Bitmap render(int[] cells, float scale) {
        if (cells.length != 50) throw new IllegalArgumentException("need 50 cells");
        float h=60f*scale, sep=11f*scale, outer=26f*scale, side=24f*scale, topBlack=18f*scale, bottomBlack=34f*scale;
        float base=2f*h/(float)Math.sqrt(3), dx=base/2f;
        float latticeW=base+9f*dx;
        float blackW=2f*side+latticeW;
        float blackH=topBlack+bottomBlack+5f*h+6f*sep;
        int W=(int)Math.ceil(blackW+2f*outer), H=(int)Math.ceil(blackH+2f*outer);
        Bitmap bmp=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bmp);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(Color.WHITE);
        p.setColor(Color.BLACK);
        c.drawRect(outer,outer,outer+blackW,outer+blackH,p);
        float ySep0=outer+topBlack;
        p.setColor(Color.WHITE);
        for(int r=0;r<6;r++){
            float y=ySep0+r*(sep+h);
            c.drawRect(outer,y,outer+blackW,y+sep,p);
        }
        float x0=outer+side;
        for(int idx=0;idx<50;idx++){
            int row=idx/10,col=idx%10;
            float triTop=ySep0+row*(sep+h)+sep;
            float triBottom=triTop+h;
            float x=x0+col*dx;
            Path path=new Path();
            if((col&1)==0){
                path.moveTo(x,triTop); path.lineTo(x+base,triTop); path.lineTo(x+base/2f,triBottom);
            }else{
                path.moveTo(x,triBottom); path.lineTo(x+base,triBottom); path.lineTo(x+base/2f,triTop);
            }
            path.close();
            p.setColor(PALETTE[cells[idx]]);
            c.drawPath(path,p);
        }
        return bmp;
    }
}
