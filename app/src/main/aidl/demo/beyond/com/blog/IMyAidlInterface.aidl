// IMyAidlInterface.aidl
package demo.beyond.com.blog;
import demo.beyond.com.blog.IMyListener;
// Declare any non-default types here with import statements

interface IMyAidlInterface {
    void operation(int parameter1 , int parameter2,IMyListener listener);
}
