package org.wangshu.scan;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

/** Targeted in-memory edits paired with exactly one manifest write. Originals are never touched. */
final class DocumentMutations {
    interface Writer { void save(Models.Document document) throws Exception; }
    private interface Rollback { boolean restoreIfOwned(); }
    static final class SaveFailure extends Exception {
        private static final long serialVersionUID=1L;
        final boolean targetRestored;
        SaveFailure(Exception cause,boolean restored){
            super((restored?"保存未完成，本次更改已撤回，原图保留":"保存未完成，文稿已有后续变化，未覆盖这些变化；请检查文稿")+(cause.getMessage()==null?"":"\n"+cause.getMessage()),cause);
            targetRestored=restored;
        }
    }
    private DocumentMutations(){}
    private static boolean save(Models.Document document,Writer writer,Rollback rollback)throws Exception{
        try{writer.save(document);return true;}
        catch(ManifestFence.Uncertain uncertain){if(!document.manifestUncertain)rollback.restoreIfOwned();document.manifestUncertain=true;throw uncertain;}
        catch(Exception failure){if(document.manifestUncertain)throw failure;throw new SaveFailure(failure,rollback.restoreIfOwned());}
        catch(Error failure){if(!document.manifestUncertain)rollback.restoreIfOwned();throw failure;}
    }
    static String title(String input)throws IOException{
        if(input==null)throw new IOException("文稿名称不可为空");
        String value=input.trim();
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw new IOException("文稿名称包含无效字符");}
            else if(Character.isLowSurrogate(c))throw new IOException("文稿名称包含无效字符");
        }
        int count=value.codePointCount(0,value.length());
        return value.substring(0,value.offsetByCodePoints(0,Math.min(count,100)));
    }
    static boolean rename(Models.Document document,String input,Writer writer)throws Exception{
        synchronized(Store.FILE_LOCK){
            Store.requireCertain(document);
            String next=title(input),before=document.title;
            if(next.isEmpty()||Objects.equals(before,next))return false;
            document.title=next;
            return save(document,writer,()->{if(Objects.equals(document.title,next))document.title=before;return Objects.equals(document.title,before);});
        }
    }
    static boolean removed(Models.Document document,Models.Page page,boolean value,Writer writer)throws Exception{
        synchronized(Store.FILE_LOCK){
            Store.requireCertain(document);
            if(!document.pages.contains(page))throw new IOException("页面已变化，请重新打开文稿");
            boolean before=page.removed;if(before==value)return false;page.removed=value;
            return save(document,writer,()->{if(document.pages.contains(page)&&page.removed==value)page.removed=before;return page.removed==before;});
        }
    }
    static boolean archived(Models.Document document,boolean value,Writer writer)throws Exception{
        synchronized(Store.FILE_LOCK){
            Store.requireCertain(document);
            boolean before=document.archived;if(before==value)return false;document.archived=value;
            return save(document,writer,()->{if(document.archived==value)document.archived=before;return document.archived==before;});
        }
    }
    static boolean move(Models.Document document,Models.Page page,int direction,Writer writer)throws Exception{
        synchronized(Store.FILE_LOCK){
            Store.requireCertain(document);
            if(direction!=-1&&direction!=1)throw new IOException("不支持的页面移动方向");
            ArrayList<Models.Page> active=document.active();int from=active.indexOf(page),to=from+direction;
            if(from<0)throw new IOException("页面已变化，请重新打开文稿");
            if(to<0||to>=active.size())return false;
            Models.Page other=active.get(to);int first=document.pages.indexOf(page),second=document.pages.indexOf(other);
            document.pages.set(first,other);document.pages.set(second,page);
            return save(document,writer,()->{
                if(document.pages.size()<=Math.max(first,second))return false;
                if(document.pages.get(first)==other&&document.pages.get(second)==page){document.pages.set(first,page);document.pages.set(second,other);return true;}
                return document.pages.get(first)==page&&document.pages.get(second)==other;
            });
        }
    }
    static boolean replacePage(Models.Document document,Models.Page before,Models.Page next,Writer writer)throws Exception{
        synchronized(Store.FILE_LOCK){
            Store.requireCertain(document);
            int index=document.pages.indexOf(before);
            if(index<0||next==null||!Objects.equals(before.id,next.id))throw new IOException("页面已变化，请重新打开文稿");
            if(before==next)return false;document.pages.set(index,next);
            return save(document,writer,()->{
                if(index>=document.pages.size())return false;
                if(document.pages.get(index)==next)document.pages.set(index,before);
                return document.pages.get(index)==before;
            });
        }
    }
    static boolean ocr(Models.Document document,Models.Page page,String text,String key,String language,Writer writer)throws Exception{
        synchronized(Store.FILE_LOCK){
            Store.requireCertain(document);
            if(!document.pages.contains(page))throw new IOException("页面已变化，请重新打开文稿");
            String oldText=page.ocrText,oldKey=page.ocrKey,oldLanguage=page.ocrLanguage;
            String nextText=text==null?"":text,nextKey=key==null?"":key,nextLanguage=language==null?"":language;
            page.ocrText=nextText;page.ocrKey=nextKey;page.ocrLanguage=nextLanguage;
            return save(document,writer,()->{
                if(!document.pages.contains(page))return false;
                if(Objects.equals(page.ocrText,nextText))page.ocrText=oldText;
                if(Objects.equals(page.ocrKey,nextKey))page.ocrKey=oldKey;
                if(Objects.equals(page.ocrLanguage,nextLanguage))page.ocrLanguage=oldLanguage;
                return Objects.equals(page.ocrText,oldText)&&Objects.equals(page.ocrKey,oldKey)&&Objects.equals(page.ocrLanguage,oldLanguage);
            });
        }
    }
    static int appendRecovered(Models.Document document,ArrayList<Models.Page> candidates,Writer writer)throws Exception{
        synchronized(Store.FILE_LOCK){
            Store.requireCertain(document);
            ArrayList<Models.Page> added=new ArrayList<>();
            for(Models.Page page:candidates){
                boolean duplicate=false;
                for(Models.Page prior:document.pages)if(Objects.equals(prior.id,page.id)||Objects.equals(prior.file,page.file)){
                    if(!Objects.equals(prior.file,page.file)||!Objects.equals(prior.sha256,page.sha256))throw new IOException("已有不同原图，未覆盖");
                    duplicate=true;break;
                }
                if(!duplicate)for(Models.Page prior:added)if(Objects.equals(prior.id,page.id)||Objects.equals(prior.file,page.file)){
                    if(!Objects.equals(prior.file,page.file)||!Objects.equals(prior.sha256,page.sha256))throw new IOException("待恢复原图编号冲突，未覆盖");
                    duplicate=true;break;
                }
                if(!duplicate)added.add(page);
            }
            if(added.isEmpty())return 0;
            document.pages.addAll(added);
            save(document,writer,()->{for(Models.Page page:added)document.pages.remove(page);for(Models.Page page:added)if(document.pages.contains(page))return false;return true;});
            return added.size();
        }
    }
}
