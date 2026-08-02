package net.programmierecke.radiodroid2.playlist;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;

/**
 * Created by segler on 04.03.17.
 */

public class PlaylistM3U {
    final static String COMMENTMARKER = "#";
    final static String EXTENDED = "#EXTM3U";

    String fullText;
    URL path;
    boolean extended = false;
    ArrayList<PlaylistM3UEntry> entries = new ArrayList<PlaylistM3UEntry>();
    String header = null;

    public PlaylistM3U(URL _path, String _fullText){
        path = _path;
        fullText = _fullText;
        decode();
    }

    void decode(){
        String[] lines = getLines();
        for (String line : lines) {
            try {
                decodeLine(line);
            } catch (MalformedURLException e) {
                // 单行解析失败不应中断整个播放列表解析
            } catch (RuntimeException e) {
                // 防御 getBasePath 等处的边界异常，避免单行错误导致整列表解析失败
            }
        }
    }

    URL resolveToBase(String file) throws MalformedURLException {
        String oldPath = path.getPath();
        String filePath = getBasePath(oldPath) + "/" + file;
        return new URL(path.getProtocol(),path.getHost(),path.getPort(),filePath);
    }

    void decodeLine(String line) throws MalformedURLException {
        if (line.startsWith(EXTENDED)){
            extended = true;
        }else if (line.startsWith(COMMENTMARKER)){
            if (extended){
                header = line;
            }
        }else{
            String lineLower = line.toLowerCase();
            if (lineLower.startsWith("http://") || lineLower.startsWith("https://")){
                entries.add(new PlaylistM3UEntry(header, line));
            }else{
                entries.add(new PlaylistM3UEntry(header, resolveToBase(line).toString()));
            }
            header = null;
        }
    }

    String getBasePath(String fullPath) {
        final char pathSeparator = '/';
        int sep = fullPath.lastIndexOf(pathSeparator);
        // 路径不含分隔符时返回空串，避免 substring(0, -1) 抛 StringIndexOutOfBoundsException
        if (sep < 0) {
            return "";
        }
        return fullPath.substring(0, sep);
    }

    String[] getLines(){
        StringReader r = new StringReader(fullText);
        BufferedReader br = new BufferedReader(r);
        ArrayList<String> list = new ArrayList<String>();
        String line;
        try {
            while ((line = br.readLine()) != null){
                list.add(line);
            }
        } catch (IOException e) {
            // 静默失败：StringReader 不会抛出真实的 IOException
        }
        return list.toArray(new String[0]);
    }

    public PlaylistM3UEntry[] getEntries(){
        return entries.toArray(new PlaylistM3UEntry[0]);
    }
}
