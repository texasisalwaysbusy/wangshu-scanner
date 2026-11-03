package org.wangshu.scan;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Read-only presentation snapshots. Labels never authorize loading or replaying a draft. */
final class EditRecoveryCatalog {
    enum Status { BASELINE, SAVED, CONFLICT, PAGE_GONE, SOURCE_UNVERIFIED, DOCUMENT_UNAVAILABLE, ARCHIVED, INVALID }
    static final class Page {
        final EditRecoveryPolicy.Identity source;final String json;final boolean removed,sourceVerified;
        Page(EditRecoveryPolicy.Identity s,String j,boolean r,boolean v){source=s;json=j;removed=r;sourceVerified=v;}
    }
    static final class Current {
        final String owner,title,version;final boolean editable;final Page[] pages;
        Current(String o,String t,String v,boolean e,Page[] p){owner=o;title=t;version=v;editable=e;pages=p;}
    }
    static final class Record {
        final EditDraftStore.Entry entry;final EditDraftStore.Draft draft;
        final EditRecoveryPolicy.Identity baseline,edited;final boolean removed;
        Record(EditDraftStore.Entry e,EditDraftStore.Draft d,EditRecoveryPolicy.Identity b,EditRecoveryPolicy.Identity x,boolean r){entry=e;draft=d;baseline=b;edited=x;removed=r;}
    }
    static final class Label {
        final String document,page,time;final Status status;
        Label(String d,String p,String t,Status s){document=d;page=p;time=t;status=s;}
        String text(){return document+" · "+page+"\n"+time+" · "+statusText(status);}
    }
    static String clean(String title){
        String text=title==null?"":title.replaceAll("[\\p{Cntrl}\\p{Cf}]"," ").trim();
        if(text.isEmpty())return "未命名文稿";
        int n=text.codePointCount(0,text.length());return n>64?text.substring(0,text.offsetByCodePoints(0,64))+"…":text;
    }
    static String document(String owner,Current current){
        String tail=owner==null?"未知":owner.substring(Math.max(0,owner.length()-8));
        return (current==null?"文稿暂不可读取":"当前标题："+clean(current.title))+"〔"+tail+"〕";
    }
    static String time(long created){return created<=0?"首次保留时间未知":"首次保留 "+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(new Date(created))+"（本地）";}
    static Label describe(String owner,Record record,Current current){
        String title=document(owner,current),page="页码未知",when=time(record==null||record.entry==null?0:record.entry.created);
        if(owner==null||record==null||record.entry==null||!record.entry.valid||record.draft==null
                ||!owner.equals(record.draft.document)||record.removed
                ||!EditRecoveryPolicy.sameSource(record.baseline,record.edited))return new Label(title,page,when,Status.INVALID);
        if(current==null||!owner.equals(current.owner))return new Label(title,page,when,Status.DOCUMENT_UNAVAILABLE);
        Page found=null;int position=0,matches=0;
        for(Page p:current.pages){if(!p.removed)position++;if(p.source!=null&&record.edited.page.equals(p.source.page)){found=p;matches++;if(!p.removed)page="当前第 "+position+" 页";}}
        if(matches>1)return new Label(title,"页面身份重复",when,Status.CONFLICT);
        if(found==null||found.removed)return new Label(title,"页面已移除或不存在",when,Status.PAGE_GONE);
        if(!current.editable)return new Label(title,page,when,Status.ARCHIVED);
        if(!EditRecoveryPolicy.sameSource(record.baseline,found.source)||!found.sourceVerified)return new Label(title,page,when,Status.SOURCE_UNVERIFIED);
        EditRecoveryPolicy.Decision decision=EditRecoveryPolicy.decide(record.baseline,found.source,record.draft.version,current.version,
                record.draft.baseline,record.draft.edited,found.json,record.draft.submitted);
        Status status=decision==EditRecoveryPolicy.Decision.BASELINE?Status.BASELINE:
                decision==EditRecoveryPolicy.Decision.CONTENTS_SAVED?Status.SAVED:Status.CONFLICT;
        return new Label(title,page,when,status);
    }
    static String statusText(Status s){switch(s){
        case BASELINE:return "原版本一致 · 仍须重新核对后手动载入";
        case SAVED:return "内容已保存 · 不重放旧编辑";
        case CONFLICT:return "版本或页面冲突 · 仅查看/导出";
        case PAGE_GONE:return "页面已移除或不存在 · 仅查看/导出";
        case SOURCE_UNVERIFIED:return "原图身份未通过核对 · 仅查看/导出";
        case DOCUMENT_UNAVAILABLE:return "文稿暂不可读取 · 不判断已保存";
        case ARCHIVED:return "文稿已隐藏或暂不可编辑 · 仅查看/导出";
        default:return "记录损坏或身份无效 · 原文件保留";
    }}
    static String quota(EditDraftStore.Usage u){
        if(!u.complete)return "恢复区余量未知：已观察至少 "+u.count+" 项、"+u.bytes+" 字节；存在无法计量的条目，不能据此继续新写入。";
        return "恢复记录 "+u.count+" / "+u.fileLimit+" 项 · 剩余 "+u.remainingFiles()+" 项\n"
                +"已用 "+u.bytes+" / "+u.byteLimit+" 字节 · 剩余 "+u.remainingBytes()+" 字节\n"
                +"单条上限 "+u.recordLimit+" 字节；损坏和未知名称的普通文件也计入额度。\n"
                +(u.full()?"已达上限：需要新增记录的保存会被阻止，已有记录保留；相同记录重试仍核对去重。":u.near()?"接近上限：后续新记录可能阻止页面保存，请先核对重要记录；参数副本导出不会释放额度。":"余量仅为本次核对快照，不能保证当前编辑可容纳；写入前仍会重新检查。 ");
    }
    private EditRecoveryCatalog(){}
}
