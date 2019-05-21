// IMyAidlInterface.aidl
package demo.beyond.com.blog;

// Declare any non-default types here with import statements

interface IMyAidlInterface {
    void operation(int parameter1 , int parameter2);


    void register(IMyOnListener listener);
    void unregister(IMyOnListener listener);
}
