.class public Lai/muse/gadget/net/SignalQueue;
.super Ljava/util/concurrent/LinkedBlockingQueue;

.field private static final END:Ljava/lang/Object;

.method static constructor <clinit>()V
    .locals 1
    new-instance v0, Ljava/lang/Object;
    invoke-direct {v0}, Ljava/lang/Object;-><init>()V
    sput-object v0, Lai/muse/gadget/net/SignalQueue;->END:Ljava/lang/Object;
    return-void
.end method

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Ljava/util/concurrent/LinkedBlockingQueue;-><init>()V
    return-void
.end method

.method public offer(Ljava/lang/Object;)Z
    .locals 1
    if-nez p1, :enqueue
    sget-object p1, Lai/muse/gadget/net/SignalQueue;->END:Ljava/lang/Object;
    :enqueue
    invoke-super {p0, p1}, Ljava/util/concurrent/LinkedBlockingQueue;->offer(Ljava/lang/Object;)Z
    move-result v0
    return v0
.end method

.method public take()Ljava/lang/Object;
    .locals 2
    invoke-super {p0}, Ljava/util/concurrent/LinkedBlockingQueue;->take()Ljava/lang/Object;
    move-result-object v0
    sget-object v1, Lai/muse/gadget/net/SignalQueue;->END:Ljava/lang/Object;
    if-ne v0, v1, :return_value
    const/4 v0, 0x0
    :return_value
    return-object v0
.end method

.method public poll(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;
    .locals 2
    invoke-super {p0, p1, p2, p3}, Ljava/util/concurrent/LinkedBlockingQueue;->poll(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;
    move-result-object v0
    sget-object v1, Lai/muse/gadget/net/SignalQueue;->END:Ljava/lang/Object;
    if-ne v0, v1, :return_value
    const/4 v0, 0x0
    :return_value
    return-object v0
.end method
