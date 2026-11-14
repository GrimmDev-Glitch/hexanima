package com.hexanima.npu;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class QwenTokCli {
    public static void main(String[] args) throws Exception {
        QwenTok tokenizer = new QwenTok(args[0]);
        int[] ids = tokenizer.encodeQwenImage(args[1]);
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(args[2]))) {
            ByteBuffer buffer = ByteBuffer.allocate(ids.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int id : ids) buffer.putInt(id);
            out.write(buffer.array());
        }
        System.out.println("tokens=" + ids.length + " drop=" + tokenizer.dropQwenImage());
    }
}
