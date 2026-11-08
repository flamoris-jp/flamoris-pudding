package net.flamoris.pudding;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class Version {
    private Version(){}
    public static final String VALUE=load();
    private static String load(){
        try(var input=Version.class.getResourceAsStream("/pudding-version.txt")) {
            if(input==null)throw new IOException("version missing");String text=new String(input.readNBytes(128),StandardCharsets.US_ASCII).strip();
            if(!text.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"))throw new IOException("version invalid");return text;
        }catch(IOException e){throw new IllegalStateException("build version configuration",e);}
    }
}
