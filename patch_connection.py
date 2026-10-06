from pathlib import Path
root=Path('patched/smali/ai/muse/gadget')
p=root/'crypto/NoiseXX.smali'
s=p.read_text()
a=s.index('    new-instance v5, Ljava/security/spec/XECPrivateKeySpec;')
b=s.index('    .line 89',a)
s=s[:a]+'''    const/16 v6, 0x30
    new-array v7, v6, [B
    fill-array-data v7, :private_prefix
    const/4 v6, 0x0
    const/16 v8, 0x10
    const/16 v9, 0x20
    invoke-static {p1, v6, v7, v8, v9}, Ljava/lang/System;->arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V
    new-instance v5, Ljava/security/spec/PKCS8EncodedKeySpec;
    invoke-direct {v5, v7}, Ljava/security/spec/PKCS8EncodedKeySpec;-><init>([B)V
    invoke-virtual {v1, v5}, Ljava/security/KeyFactory;->generatePrivate(Ljava/security/spec/KeySpec;)Ljava/security/PrivateKey;
    move-result-object v5
    const/16 v6, 0x2c
    new-array v7, v6, [B
    fill-array-data v7, :public_prefix
    const/4 v6, 0x0
    const/16 v8, 0xc
    invoke-static {p2, v6, v7, v8, v9}, Ljava/lang/System;->arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V
    new-instance v6, Ljava/security/spec/X509EncodedKeySpec;
    invoke-direct {v6, v7}, Ljava/security/spec/X509EncodedKeySpec;-><init>([B)V
    invoke-virtual {v1, v6}, Ljava/security/KeyFactory;->generatePublic(Ljava/security/spec/KeySpec;)Ljava/security/PublicKey;
    move-result-object v6

'''+s[b:]
a=s.index('.method public final x25519Dh(')
b=s.index('.end method',a)
arrays=''
for name,h in [('private','302e020100300506032b656e04220420'),('public','302a300506032b656e032100')]:
 arrays+='    :'+name+'_prefix\n    .array-data 1\n'+''.join('        0x%02xt\n'%v for v in bytes.fromhex(h))+'    .end array-data\n'
s=s[:b]+arrays+s[b:]
p.write_text(s)
p=root/'net/LinkSession.smali'
s=p.read_text()
import re
s=re.sub(r'(new-instance \w+, )Ljava/util/concurrent/LinkedBlockingQueue;',r'\1Lai/muse/gadget/net/SignalQueue;',s)
s=s.replace('Ljava/util/concurrent/LinkedBlockingQueue;-><init>()V','Lai/muse/gadget/net/SignalQueue;-><init>()V')
p.write_text(s)
