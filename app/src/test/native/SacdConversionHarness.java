import com.rawsmusic.transcode.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import kotlin.Unit;

public final class SacdConversionHarness {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        File wav = new File(root, "source-48k.wav"), iso = new File(root, "encoded.iso");
        ByteBuffer audio = ByteBuffer.allocate(44 + 48000 * 2 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN);
        audio.put("RIFF".getBytes()); audio.putInt(audio.capacity()-8); audio.put("WAVEfmt ".getBytes());
        audio.putInt(16); audio.putShort((short)1); audio.putShort((short)2); audio.putInt(48000);
        audio.putInt(192000); audio.putShort((short)4); audio.putShort((short)16);
        audio.put("data".getBytes()); audio.putInt(audio.capacity()-44);
        for(int i=0; i<96000; i++) {
            short sample=(short)(8000*Math.sin(2*Math.PI*440*i/48000));
            audio.putShort(sample); audio.putShort(sample);
        }
        try(FileOutputStream file = new FileOutputStream(wav)) { file.write(audio.array()); }
        AudioTranscodeProbe probe = NativeAudioTranscoder.INSTANCE.probe(wav.getPath());
        if(probe == null || probe.getSampleRateHz()!=48000) throw new AssertionError("PCM probe");
        System.out.println("INPUT_PROBE="+probe);
        try(NativeAudioTranscoder.Session session=NativeAudioTranscoder.INSTANCE.createSession()) {
            File debugPcm = new File(root,"debug-normalized.wav");
            int debugResult = session.transcodeLossless(wav.getPath(),debugPcm.getPath(),"wav",44100,32,0,false);
            System.out.println("PREPARE_PCM_RESULT="+debugResult+" probe="+NativeAudioTranscoder.INSTANCE.probe(debugPcm.getPath()));
            iso.delete();
            Map<String,String> tags = new HashMap<>();
            tags.put("title", "Encoded song"); tags.put("artist", "Test artist"); tags.put("album", "Test album");
            kotlin.Pair<Integer,SacdIsoWriter.Result> result = SacdIsoTranscode.INSTANCE.encode(session,
                probe,wav,iso,tags,()->false,()->Unit.INSTANCE,progress->Unit.INSTANCE);
            if(result.getFirst()!=0 || result.getSecond()==null || result.getSecond().getFrames()!=150)
                throw new AssertionError("SACD conversion: "+result);
            for(File file:root.listFiles()) if(file.getName().startsWith(".raws-sacd-"))
                throw new AssertionError("temporary audio leak");
            File normalized=new File(root,"reference-44k.wav"), dsf=new File(root,"reference.dsf");
            if(session.transcodeLossless(wav.getPath(),normalized.getPath(),"wav",44100,32,0,false)!=0 ||
                session.transcodeDsf(normalized.getPath(),dsf.getPath(),AudioTranscodeDsdRate.DSD64)!=0)
                throw new AssertionError("Reference DSD encoding");
        }
        System.out.println("PCM48K_TO_DSD64_TO_SACD_ISO=true frames=150 TEMPORARY_FILES_RETIRED=true");
    }
}
