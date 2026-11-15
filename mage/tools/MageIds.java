// Host helper for ADB tests of magerun: writes the templated Mage prompt token ids (int32 LE) with the app's own QwenTok
// and prints the number of system tokens to drop.
// build+run: javac -d /tmp/mt app/src/com/hexanima/npu/QwenTok.java mage/tools/MageIds.java && java -cp /tmp/mt MageIds <pack dir> "<prompt>" ids.i32
import com.hexanima.npu.QwenTok;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MageIds {
    public static void main(String[] a) throws Exception {
        QwenTok t = new QwenTok(a[0]);
        int[] ids = t.encodeMage(a[1]);
        ByteBuffer b = ByteBuffer.allocate(ids.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i : ids) b.putInt(i);
        try (FileOutputStream o = new FileOutputStream(a[2])) { o.write(b.array()); }
        System.out.println(ids.length + " ids, drop " + t.dropMage(false));
    }
}
