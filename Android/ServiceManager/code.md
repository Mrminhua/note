```c
class BBinder : public IBinder
{
    virtual BBinder* localBinder();
    virtual status_t onTransact(uint32_t code,const Parcel& data,Parcel* reply,uint32_t flags = 0);
}


```