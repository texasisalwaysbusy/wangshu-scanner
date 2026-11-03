package org.wangshu.scan;

import java.io.IOException;
import java.util.*;

/** One-page OCR is a proposal, never a document mutation. Keep the prior draft before adoption. */
final class OcrCandidate {
    static final class Request {
        final Models.Document owner;final Models.Page original,draft,frozen;final Object session;
        final String version,baseline,edited;final List<String> order;final int generation,number;final long cycle;
        Request(Models.Document owner,Object session,Models.Page original,Models.Page draft,String baseline,String editorVersion,int generation,long cycle)throws Exception {
            if(owner==null||owner.archived||owner.manifestUncertain||session==null||original==null||draft==null
                    ||owner.savedSnapshot.isEmpty()||!owner.savedSnapshot.equals(editorVersion)||baseline==null
                    ||!baseline.equals(original.json().toString())||!owner.active().contains(original)
                    ||!original.id.equals(draft.id)||!original.file.equals(draft.file)||!original.sha256.equals(draft.sha256)
                    ||!draft.cropConfirmed())throw new IOException("请先核对当前页四角及已保存版本；已有文字保留");
            this.owner=owner;this.session=session;this.original=original;this.draft=draft;this.baseline=baseline;
            version=editorVersion;this.generation=generation;this.cycle=cycle;order=TextExport.order(owner);
            if(new HashSet<>(order).size()!=order.size())throw new IOException("页序身份无效，未开始重识别");
            number=order.indexOf(draft.id)+1;frozen=draft.copy();edited=frozen.json().toString();
        }
        boolean current(Models.Document owner,Object session,Models.Page original,Models.Page draft,String baseline,String editorVersion,int generation,long cycle) {
            try{return this.owner==owner&&this.session==session&&this.original==original&&this.draft==draft
                    &&owner!=null&&!owner.archived&&!owner.manifestUncertain&&owner.active().contains(original)
                    &&version.equals(owner.savedSnapshot)&&version.equals(editorVersion)&&version.equals(EditorSaveFlow.observedVersion(owner.id))&&this.baseline.equals(baseline)
                    &&this.baseline.equals(original.json().toString())&&edited.equals(draft.json().toString())
                    &&order.equals(TextExport.order(owner))&&this.generation==generation&&this.cycle==cycle;
            }catch(Exception e){return false;}
        }
        Result result(String text,String language)throws IOException {
            if(text==null)throw new IOException("重识别未返回结果；已有文字保留");
            if(!"eng".equals(language)&&!"chi_sim+eng".equals(language))throw new IOException("识别语言不受支持");
            return new Result(this,text,language);
        }
    }
    interface Preflight {void check(Models.Page next,EditDraftStore.Draft record)throws Exception;}
    static final class Adoption {
        final Result result;final Models.Page next;final EditDraftStore.Entry previous;
        private Adoption(Result result,Models.Page next,EditDraftStore.Entry previous){this.result=result;this.next=next;this.previous=previous;}
    }
    static final class Result {
        final Request request;final String text,language;private boolean applied;
        private Result(Request request,String text,String language){this.request=request;this.text=text;this.language=language;}
        /** Worker-only. Validation or retention failure leaves the live draft completely untouched. */
        Adoption retainPrevious(EditDraftStore records,Preflight preflight)throws Exception {
            if(applied)throw new IOException("此候选已处理，请重新识别");
            Models.Page next=request.frozen.copy();next.ocrText=text;next.ocrKey=OcrEngine.key(next);next.ocrLanguage=language;next.ocrOrigin="machine";
            EditDraftStore.Draft proposed=new EditDraftStore.Draft(request.owner.id,request.version,request.baseline,next.json().toString(),false,0);
            EditDraftStore.validate(proposed);preflight.check(next,proposed);
            EditDraftStore.Entry kept=records.retain(request.owner.id,request.version,request.baseline,request.edited,false);
            return new Adoption(this,next,kept);
        }
        /** UI-only one-shot delivery. Caller also checks its active proposal and lifecycle readiness. */
        boolean apply(Adoption value,Models.Document owner,Object session,Models.Page original,Models.Page draft,String baseline,String editorVersion,int generation,long cycle) {
            if(applied||value==null||value.result!=this||value.previous==null||!value.previous.valid
                    ||!request.current(owner,session,original,draft,baseline,editorVersion,generation,cycle))return false;
            draft.ocrText=value.next.ocrText;draft.ocrKey=value.next.ocrKey;draft.ocrLanguage=value.next.ocrLanguage;draft.ocrOrigin="machine";
            applied=true;return true;
        }
    }
}
