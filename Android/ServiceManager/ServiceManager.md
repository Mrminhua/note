# 系统原理

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
```

## 添加服务addService

```c
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
    }                                                                                              \
```