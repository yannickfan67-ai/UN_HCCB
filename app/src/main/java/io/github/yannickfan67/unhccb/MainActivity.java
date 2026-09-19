package io.github.yannickfan67.unhccb;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PICK=10, REQ_CAMERA=11;
    private ImageView preview;
    private TextView result;
    private EditText prefixInput,codeInput;
    private Bitmap currentBitmap;
    private Uri cameraUri;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        preview=findViewById(R.id.previewImage);
        result=findViewById(R.id.resultText);
        prefixInput=findViewById(R.id.prefixInput);
        codeInput=findViewById(R.id.codeInput);
        findViewById(R.id.pickButton).setOnClickListener(v->pickImage());
        findViewById(R.id.cameraButton).setOnClickListener(v->takePhoto());
        findViewById(R.id.generateButton).setOnClickListener(v->generate());
        findViewById(R.id.saveButton).setOnClickListener(v->saveCurrent());
        generate();
    }

    private void pickImage(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,REQ_PICK);
    }

    private void takePhoto(){
        try{
            ContentValues cv=new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME,"UN_HCCB_scan_"+System.currentTimeMillis()+".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
            cameraUri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv);
            Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT,cameraUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i,REQ_CAMERA);
        }catch(Exception e){ showError(e); }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK)return;
        Uri uri=requestCode==REQ_PICK && data!=null ? data.getData() : (requestCode==REQ_CAMERA ? cameraUri : null);
        if(uri==null)return;
        try{
            Bitmap b=ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(),uri),
                    (decoder,info,src)->decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            currentBitmap=b;preview.setImageBitmap(b);decodeBitmap(b);
        }catch(Exception e){showError(e);}
    }

    private void decodeBitmap(Bitmap b){
        result.setText("解碼中…");
        new Thread(()->{
            try{
                TagImageDecoder.ImageResult r=TagImageDecoder.decode(b);
                HccbCodec.DecodeResult d=r.decoded;
                String text="Tag ID: "+d.tid+"\nPrefix: "+d.prefix+"\nCode: "+d.code+
                        "\nCRC: "+(d.crcOk?"OK":"FAIL")+"\nRS parity: "+(d.parityOk?"match":"damaged/edited")+
                        "\nCorrected cells: "+d.correctedCells;
                runOnUiThread(()->result.setText(text));
            }catch(Exception e){runOnUiThread(()->result.setText("解碼失敗："+e.getMessage()));}
        }).start();
    }

    private void generate(){
        try{
            int prefix=Integer.parseInt(prefixInput.getText().toString().trim());
            long code=Long.parseLong(codeInput.getText().toString().trim());
            int[] cells=HccbCodec.encode(prefix,code);
            currentBitmap=TagRenderer.render(cells,2.2f);
            preview.setImageBitmap(currentBitmap);
            HccbCodec.DecodeResult check=HccbCodec.decode(cells);
            result.setText("已生成："+check.tid+"\nCRC / RS / calibration: OK");
        }catch(Exception e){showError(e);}
    }

    private void saveCurrent(){
        if(currentBitmap==null){Toast.makeText(this,"沒有可儲存的圖片",Toast.LENGTH_SHORT).show();return;}
        try{
            String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
            ContentValues cv=new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME,"UN_HCCB_"+stamp+".png");
            cv.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/UN_HCCB");
            Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv);
            if(uri==null)throw new IllegalStateException("MediaStore insert failed");
            try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null || !currentBitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("PNG write failed");
            }
            Toast.makeText(this,"已存到 Pictures/UN_HCCB",Toast.LENGTH_LONG).show();
        }catch(Exception e){showError(e);}
    }

    private void showError(Exception e){
        result.setText("錯誤："+e.getMessage());
        Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();
    }
}
