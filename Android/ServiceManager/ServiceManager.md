# 系统原理
Binder机制通过共享内存的方式实现了跨进程通信，数据在客户端进程和服务端进程只需要拷贝一次，其内存共享数据拷贝都在内核空间实现。首先系统在启动的时候会启动一个ServiceManager进程，让该进程和Binder驱动建立关系，并保存在Binder驱动当中，该进程的标识handle为0，让服务的BnServiceManager进入循环并在驱动测阻塞。Client进程通过调用ServiceManager的接口，获取到BpBinder的实现类，并将Server进程的标识0、数据指针和方法标识code传递给内核驱动，此时就说明需要驱动找到ServiceManager作为Server并调用code标识的方法进行业务处理，驱动收到事务后将客户端传递的数据拷贝到ServiceManager的进程当中，紧接着客户端线程会进入阻塞状态，同时唤醒正在处于阻塞状态的ServiceManager进程线程，此时已经从客户端进程转入到服务端进程当中，被唤醒的ServiceManager进程的BnServiceManager就开始执行对应的方法，最后将执行结果拷贝到Client的内存当中，唤醒正在阻塞的客户端进程并进入阻塞状态。经过一次这样的流程后就完成了【Client进程--Server进程-Client进程】的一次数据交换和业务处理。当然获取其他服务也是通过给ServiceManager进程获取到对应Server的BpBinder实现类，从而从驱动当中找到对应的BnBinder的实现类进行对应的操作。

# 架构设计


# 详细实现

## 启动ServiceManager

``` c++
int main(int argc, char** argv) {
    const char* driver = argc == 2 ? argv[1] : "/dev/binder";
    // 与驱动建立联系
    sp<ProcessState> ps = ProcessState::initWithDriver(driver);
    ps->setThreadPoolMaxThreadCount(0);
    ps->setCallRestriction(ProcessState::CallRestriction::FATAL_IF_NOT_ONEWAY);
    // 将自己添加到ServiceManager
    sp<ServiceManager> manager = sp<ServiceManager>::make(std::make_unique<Access>());
    if (!manager->addService("manager", manager, false /*allowIsolated*/, IServiceManager::DUMP_FLAG_PRIORITY_DEFAULT).isOk()) {
        LOG(ERROR) << "Could not self register servicemanager";
    }
    // 设置为context
    IPCThreadState::self()->setTheContextObject(manager);
    ps->becomeContextManager();

    sp<Looper> looper = Looper::prepare(false /*allowNonCallbacks*/);

    BinderCallback::setupTo(looper);
    ClientCallbackCallback::setupTo(looper, manager);
    // 开始循环
    while(true) {
        looper->pollAll(-1);
    }

    return EXIT_FAILURE;
}

```

## 获取ServiceManager

根据函数调用可以获取ServiceManager会创建或者得到之前创建的一个含有BpBinder的ServiceManagerShim对象
```c
sp<IServiceManager> gDefaultServiceManager = sp<ServiceManagerShim>::make(BpBinder::PrivateAccessor::create(0))

sp<IServiceManager> defaultServiceManager()
{
    sp<AidlServiceManager> sm = nullptr;
    while (sm == nullptr) {
        sm = interface_cast<AidlServiceManager>(ProcessState::self()->getContextObject(nullptr));
        if (sm == nullptr) {
            ALOGE("Waiting 1s on context object on %s.", ProcessState::self()->getDriverName().c_str());
            sleep(1);
        }
    }
    gDefaultServiceManager = sp<ServiceManagerShim>::make(sm);
    return g]erviceManager;
}

sp<IBinder> ProcessState::getContextObject(const sp<IBinder>& /*caller*/)
{
    sp<IBinder> context = getStrongProxyForHandle(0);
    return context;
}

sp<IBinder> ProcessState::getStrongProxyForHandle(int32_t handle)
{
    sp<IBinder> result;
    AutoMutex _l(mLock);
    if (handle == 0 && the_context_object != nullptr) return the_context_object;
    handle_entry* e = lookupHandleLocked(handle);
    if (e != nullptr) {
        IBinder* b = e->binder;
        if (b == nullptr || !e->refs->attemptIncWeak(this)) {
            sp<BpBinder> b = BpBinder::PrivateAccessor::create(handle);
            e->binder = b.get();
            if (b) e->refs = b->getWeakRefs();
            result = b;
        } else {
            result.force_set(b);
            e->refs->decWeak(this);
        }
    }
    return result;
}

DECLARE_META_INTERFACE(ServiceManager)

#define IMPLEMENT_META_INTERFACE(INTERFACE, NAME)  \
    DO_NOT_DIRECTLY_USE_ME_IMPLEMENT_META_INTERFACE(INTERFACE, NAME)    \

#define DO_NOT_DIRECTLY_USE_ME_IMPLEMENT_META_INTERFACE(INTERFACE, NAME)                       
    DO_NOT_DIRECTLY_USE_ME_IMPLEMENT_META_INTERFACE0(I##INTERFACE, I##INTERFACE, Bp##INTERFACE)

#define DO_NOT_DIRECTLY_USE_ME_IMPLEMENT_META_INTERFACE0(ITYPE, INAME, BPTYPE)                     \
    ::android::sp<ITYPE> ITYPE::asInterface(const ::android::sp<::android::IBinder>& obj) {        \
        ::android::sp<ITYPE> intr;                                                                 \
        if (obj != nullptr) {                                                                      \
            intr = ::android::sp<ITYPE>::cast(obj->queryLocalInterface(ITYPE::descriptor));        \
            if (intr == nullptr) {                                                                 \
                intr = ::android::sp<BPTYPE>::make(obj);                                           \
            }                                                                                      \
        }                                                                                          \
        return intr;                                                                               \
    }  
```

## 添加服务(BpServiceManager::addService)

```c++
class BpServiceManager : public BpInterface<IServiceManager>
{
    virtual status_t addService(const String16& name, const sp<IBinder>& service,bool allowIsolated)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IServiceManager::getInterfaceDescriptor());
        data.writeString16(name);
        data.writeStrongBinder(service);
        data.writeInt32(allowIsolated ? 1 : 0);
        status_t err = remote()->transact(ADD_SERVICE_TRANSACTION, data, &reply);
        return err == NO_ERROR ? reply.readExceptionCode() : err;
    }
}

status_t Parcel::writeStrongBinder(const sp<IBinder>& val)
{
    return flattenBinder(val);
}

status_t Parcel::flattenBinder(const sp<IBinder>& binder) {
    BBinder* local = nullptr;
    if (binder) local = binder->localBinder();

    flat_binder_object obj;

    if (binder != nullptr) {
        if (!local) {
        } else {
            obj.hdr.type = BINDER_TYPE_BINDER;
            obj.binder = reinterpret_cast<uintptr_t>(local->getWeakRefs());
            obj.cookie = reinterpret_cast<uintptr_t>(local);
        }
    }

    status_t status = writeObject(obj, false);
    if (status != OK) return status;

    return finishFlattenBinder(binder);
}

status_t BpBinder::transact(uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    status = IPCThreadState::self()->transact(binderHandle(), code, data, reply, flags);
}

status_t IPCThreadState::transact(int32_t handle,uint32_t code, const Parcel& data,Parcel* reply, uint32_t flags)
{
    status_t err;
    err = writeTransactionData(BC_TRANSACTION, flags, handle, code, data, nullptr);
    if (reply) {
        err = waitForResponse(reply);
    } else {
        Parcel fakeReply;
        err = waitForResponse(&fakeReply);
    }
}

status_t IPCThreadState::writeTransactionData(int32_t cmd, uint32_t binderFlags,int32_t handle, uint32_t code, const Parcel& data, status_t* statusBuffer)
{
    binder_transaction_data tr;

    tr.target.ptr = 0; 
    tr.target.handle = handle;// 0
    tr.code = code; // ADD_SERVICE_TRANSACTION
    mOut.writeInt32(cmd);// BC_TRANSACTION
    mOut.write(&tr, sizeof(tr));
    return NO_ERROR;
}

status_t IPCThreadState::waitForResponse(Parcel *reply, status_t *acquireResult)
{
    uint32_t cmd;
    int32_t err;

    while (1) {
        if ((err=talkWithDriver()) < NO_ERROR) break;
        err = mIn.errorCheck();
        if (err < NO_ERROR) break;
        if (mIn.dataAvail() == 0) continue;
        cmd = (uint32_t)mIn.readInt32();
    }
    return err;
}

status_t IPCThreadState::talkWithDriver(bool doReceive) // false
{
    binder_write_read bwr;
    const bool needRead = mIn.dataPosition() >= mIn.dataSize();
    const size_t outAvail = (!doReceive || needRead) ? mOut.dataSize() : 0;

    bwr.write_size = outAvail;
    bwr.write_buffer = (uintptr_t)mOut.data();
    bwr.write_consumed = 0;
    bwr.read_consumed = 0;
    status_t err;
    do {
        if (ioctl(mProcess->mDriverFD, BINDER_WRITE_READ, &bwr) >= 0)
            err = NO_ERROR;
        else
            err = -errno;
    } while (err == -EINTR);
    return err;
}
```