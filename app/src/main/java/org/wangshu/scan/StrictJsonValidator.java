package org.wangshu.scan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.HashSet;

/** Bounded RFC 8259 syntax check; rejects duplicate decoded member names. */
final class StrictJsonValidator {
    private static final int MAX_BYTES=2*1024*1024,MAX_DEPTH=32,MAX_TOKENS=100000;
    private final String text;private int at,tokens;
    private StrictJsonValidator(String text){this.text=text;}
    static void validate(byte[] raw)throws IOException{
        if(raw==null||raw.length>MAX_BYTES)throw new IOException("候选过大");
        try{
            CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            StrictJsonValidator parser=new StrictJsonValidator(decoder.decode(ByteBuffer.wrap(raw)).toString());parser.ws();parser.value(0);parser.ws();if(parser.at!=parser.text.length())throw new IOException("候选有尾随内容");
        }catch(CharacterCodingException e){throw new IOException("候选不是有效 UTF-8",e);}
    }
    private void ws(){while(at<text.length()){char c=text.charAt(at);if(c==' '||c=='\n'||c=='\r'||c=='\t')at++;else break;}}
    private boolean take(char c){if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
    private IOException invalid(){return new IOException("候选 JSON 语法无效");}
    private void value(int depth)throws IOException{
        if(++tokens>MAX_TOKENS||at>=text.length())throw invalid();char c=text.charAt(at);
        if(c=='{'||c=='['){if(depth>=MAX_DEPTH)throw new IOException("候选嵌套过深");if(c=='{')object(depth+1);else array(depth+1);return;}
        if(c=='"'){string();return;}if(c=='t'){literal("true");return;}if(c=='f'){literal("false");return;}if(c=='n'){literal("null");return;}if(c=='-'||(c>='0'&&c<='9')){number();return;}throw invalid();
    }
    private void object(int depth)throws IOException{
        at++;ws();if(take('}'))return;HashSet<String> names=new HashSet<>();
        do{ws();if(at>=text.length()||text.charAt(at)!='"')throw invalid();String key=string();if(!names.add(key))throw new IOException("候选含重复字段");ws();if(!take(':'))throw invalid();ws();value(depth);ws();if(take('}'))return;if(!take(','))throw invalid();}while(true);
    }
    private void array(int depth)throws IOException{
        at++;ws();if(take(']'))return;do{ws();value(depth);ws();if(take(']'))return;if(!take(','))throw invalid();}while(true);
    }
    private String string()throws IOException{
        if(!take('"'))throw invalid();StringBuilder out=new StringBuilder();boolean closed=false;
        while(at<text.length()){char c=text.charAt(at++);if(c=='"'){closed=true;break;}if(c<0x20)throw invalid();
            if(c=='\\'){if(at>=text.length())throw invalid();char e=text.charAt(at++);switch(e){case '"':case '\\':case '/':out.append(e);break;case 'b':out.append('\b');break;case 'f':out.append('\f');break;case 'n':out.append('\n');break;case 'r':out.append('\r');break;case 't':out.append('\t');break;case 'u':int value=0;for(int i=0;i<4;i++){if(at>=text.length())throw invalid();int digit=hex(text.charAt(at++));if(digit<0)throw invalid();value=(value<<4)|digit;}out.append((char)value);break;default:throw invalid();}}
            else out.append(c);
        }
        if(!closed)throw invalid();for(int i=0;i<out.length();i++){char c=out.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=out.length()||!Character.isLowSurrogate(out.charAt(i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}return out.toString();
    }
    private static int hex(char c){if(c>='0'&&c<='9')return c-'0';if(c>='a'&&c<='f')return c-'a'+10;if(c>='A'&&c<='F')return c-'A'+10;return -1;}
    private void literal(String value)throws IOException{if(!text.startsWith(value,at))throw invalid();at+=value.length();}
    private void number()throws IOException{
        take('-');if(at>=text.length())throw invalid();if(take('0')){if(at<text.length()&&Character.isDigit(text.charAt(at)))throw invalid();}else{if(!digit19())throw invalid();while(digit()){} }
        if(take('.')){if(!digit())throw invalid();while(digit()){} }if(take('e')||take('E')){if(!take('+'))take('-');if(!digit())throw invalid();while(digit()){} }
    }
    private boolean digit(){if(at<text.length()&&text.charAt(at)>='0'&&text.charAt(at)<='9'){at++;return true;}return false;}
    private boolean digit19(){if(at<text.length()&&text.charAt(at)>='1'&&text.charAt(at)<='9'){at++;return true;}return false;}
}
