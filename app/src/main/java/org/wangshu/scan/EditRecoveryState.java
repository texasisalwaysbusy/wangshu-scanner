package org.wangshu.scan;

/** Saved-state envelope for one held edit. A verified receipt may replace large raw strings. */
final class EditRecoveryState {
    interface Read { String get(String key); }
    interface Write { void put(String key,String value); }
    final String owner;
    volatile EditDraftStore.Draft data;
    volatile EditDraftStore.Entry entry;
    volatile boolean verified;
    EditRecoveryState(String owner,EditDraftStore.Draft data){this.owner=owner;this.data=data;}
    void retained(EditDraftStore.Entry entry){this.entry=entry;verified=true;}
    void loaded(EditDraftStore.Draft data){this.data=data;verified=true;}
    void write(Write out){
        out.put("editRecovery.owner",owner);
        EditDraftStore.Entry receipt=entry;
        if(receipt!=null){
            out.put("editRecovery.token",receipt.token);out.put("editRecovery.digest",receipt.digest);
            out.put("editRecovery.created",Long.toString(receipt.created));
        }
        EditDraftStore.Draft draft=data;
        if(draft!=null&&(!verified||receipt==null)){
            out.put("editRecovery.version",draft.version);out.put("editRecovery.baseline",draft.baseline);
            out.put("editRecovery.edited",draft.edited);out.put("editRecovery.submitted",Boolean.toString(draft.submitted));
        }
    }
    static EditRecoveryState read(Read in){
        String owner=in.get("editRecovery.owner");if(owner==null)return null;
        String baseline=in.get("editRecovery.baseline"),edited=in.get("editRecovery.edited"),version=in.get("editRecovery.version");
        EditDraftStore.Draft data=baseline!=null&&edited!=null?new EditDraftStore.Draft(owner,version,baseline,edited,
                "true".equals(in.get("editRecovery.submitted")),0):null;
        EditRecoveryState state=new EditRecoveryState(owner,data);
        String token=in.get("editRecovery.token"),digest=in.get("editRecovery.digest");long created=0;
        try{String value=in.get("editRecovery.created");if(value!=null)created=Long.parseLong(value);}catch(NumberFormatException ignored){}
        if(token!=null&&digest!=null)state.entry=new EditDraftStore.Entry(token,digest,created,null);
        // A restored reference is not a verified file until background load validates it.
        return state;
    }
}
