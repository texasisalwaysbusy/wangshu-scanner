package org.wangshu.scan;

import java.util.Objects;

/** Pure recovery identity/version policy. It never writes or rebases a page. */
final class EditRecoveryPolicy {
    enum Decision { BASELINE, CONTENTS_SAVED, CONFLICT }
    static final class Identity {
        final String document,page,file,sha;
        Identity(String d,String p,String f,String s){document=d;page=p;file=f;sha=s;}
    }
    static boolean sameSource(Identity a,Identity b){
        return a!=null&&b!=null&&a.document!=null&&a.page!=null&&a.file!=null&&a.sha!=null
                &&!a.document.isEmpty()&&!a.page.isEmpty()&&!a.file.isEmpty()&&!a.sha.isEmpty()
                &&Objects.equals(a.document,b.document)&&Objects.equals(a.page,b.page)
                &&Objects.equals(a.file,b.file)&&Objects.equals(a.sha,b.sha);
    }
    static Decision decide(Identity recorded,Identity current,String baselineVersion,String currentVersion,
            String baseline,String edited,String currentPage,boolean submitted){
        if(!sameSource(recorded,current))return Decision.CONFLICT;
        // Matching saved content is acknowledgement only: never replay an old draft over it.
        if(submitted&&Objects.equals(edited,currentPage))return Decision.CONTENTS_SAVED;
        if(baselineVersion!=null&&!baselineVersion.isEmpty()&&Objects.equals(baselineVersion,currentVersion)
                &&baseline!=null&&Objects.equals(baseline,currentPage))return Decision.BASELINE;
        return Decision.CONFLICT;
    }
    private EditRecoveryPolicy(){}
}
