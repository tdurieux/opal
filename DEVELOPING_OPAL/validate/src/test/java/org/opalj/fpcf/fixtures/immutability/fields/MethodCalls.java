package org.opalj.fpcf.fixtures.immutability.fields;

import org.opalj.fpcf.properties.field_immutability.DeepImmutableFieldAnnotation;
import org.opalj.fpcf.properties.field_immutability.MutableFieldAnnotation;
import org.opalj.fpcf.properties.field_immutability.ShallowImmutableFieldAnnotation;
import org.opalj.fpcf.properties.reference_immutability.ImmutableReferenceAnnotation;
import org.opalj.fpcf.properties.reference_immutability.LazyInitializedNotThreadSafeReferenceAnnotation;
import org.opalj.fpcf.properties.reference_immutability.LazyInitializedThreadSafeReferenceAnnotation;
import org.opalj.fpcf.properties.reference_immutability.MutableReferenceAnnotation;

public class MethodCalls {
    @ShallowImmutableFieldAnnotation("")
    @LazyInitializedThreadSafeReferenceAnnotation("")
    private TestMutable tm1;

    public synchronized void getTM1(){
        if(tm1==null){
            tm1= new TestMutable();
        }
        tm1.nop();
    }

    @ShallowImmutableFieldAnnotation("")
    @LazyInitializedThreadSafeReferenceAnnotation("")
    private TestMutable tm2;

    public synchronized TestMutable getTM2(){
        if(tm2==null){
            tm2= new TestMutable();
        }
        return tm2;
    }

    @MutableFieldAnnotation("")
    @LazyInitializedNotThreadSafeReferenceAnnotation("")
    private TestMutable tm3;

    public void getTm3() {
        if(tm3==null){
            tm3 = new TestMutable();
        }
    }

    @MutableReferenceAnnotation("")
    @MutableFieldAnnotation("")
    private TestMutable tm4;

    public synchronized TestMutable getTm4() {
        if(tm4==null){
            tm4 = new TestMutable();
        }
        return tm4;
    }

    public synchronized TestMutable getTm42() {
        if(tm4==null){
            tm4 = new TestMutable();
        }
        return tm4;
    }

    @ShallowImmutableFieldAnnotation("")
    private TestMutable tm5;

    public synchronized void getTm5() {
        if(tm5==null){
            tm5 = new TestMutable();
        }
        tm5.nop();
    }

    @DeepImmutableFieldAnnotation("")
    @ImmutableReferenceAnnotation("")
    private TestMutable tm6 = new TestMutable();

    @ShallowImmutableFieldAnnotation("")
    @ImmutableReferenceAnnotation("")
    private TestMutable tm7 = new TestMutable();

    public void foo(){
        tm7.nop();
    }








}

class TestMutable{
    private int n = 5;

    public void setN(int n){
        this.n = n;
    }

    public void nop(){
    }
}