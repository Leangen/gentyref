/*
 * License: Apache License, Version 2.0
 * See the LICENSE file in the root directory or at <a href="http://www.apache.org/licenses/LICENSE-2">apache.org</a>.
 */

package io.leangen.geantyref;

import io.leangen.geantyref.CaptureSamplesTest.Foo;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import junit.framework.TestCase;

/**
 * https://github.com/leangen/geantyref/issues/20
 */
public class Issue20Test extends TestCase {

  public interface UnitX<Q extends QuantityX<Q>> {

    Q getUnitQ();
  }

  public interface QuantityX<Q extends QuantityX<Q>> {

    Q getQuantityQ();
  }

  static class TestClass {

    public UnitX<?> returnType() {
      return null;
    }
  }

  public void testStackOverflow() throws Exception {
    AnnotatedType type = GenericTypeReflector.reduceBounded(GenericTypeReflector.annotate(
        GenericTypeReflector.getExactReturnType(TestClass.class.getMethod("returnType"),
            TestClass.class)));

    assertNotNull(type);
    assertTrue(type instanceof AnnotatedParameterizedType);
    AnnotatedParameterizedType result = (AnnotatedParameterizedType) type;

    assertSame(((ParameterizedType) result.getType()).getRawType(), UnitX.class);
    assertSame(result.getAnnotatedActualTypeArguments()[0].getType(), QuantityX.class);
  }

  public interface StevenSpecial<X extends OkType, Q extends QuantityY<Q>> {

    Q getUnitQ();

    OkType getOkType();
  }

  public interface QuantityY<Q extends QuantityY<Q>> {

    Q getQuantityQ();
  }

  public interface OkType {
    String getXxx();
  }

  static class OkClass implements OkType {
    public String getXxx() {
      return "";
    }
  }

  static class TestClass2 {

    public StevenSpecial<OkClass, ?> returnType() {
      return null;
    }
  }

  public void testStackOverflow2() throws Exception {
    AnnotatedType type = GenericTypeReflector.reduceBounded(GenericTypeReflector.annotate(
        GenericTypeReflector.getExactReturnType(TestClass2.class.getMethod("returnType"),
            TestClass2.class)));

    assertNotNull(type);
    assertTrue(type instanceof AnnotatedParameterizedType);
    AnnotatedParameterizedType result = (AnnotatedParameterizedType) type;

    assertSame(((ParameterizedType) result.getType()).getRawType(), StevenSpecial.class);
    assertSame(result.getAnnotatedActualTypeArguments()[0].getType(), OkClass.class);
    assertSame(result.getAnnotatedActualTypeArguments()[1].getType(), QuantityY.class);
  }

  public interface DoubleParameters<X extends OkType, Q extends DoubleParameters<X, Q>> {

    Q getUnitQ();

    OkType getOkType();
  }

  static class TestClass3 {

    public DoubleParameters<OkClass, ?> returnType() {
      return null;
    }
  }

  public void testStackOverflow3() throws Exception {
    AnnotatedType type = GenericTypeReflector.reduceBounded(GenericTypeReflector.annotate(
        GenericTypeReflector.getExactReturnType(TestClass3.class.getMethod("returnType"),
            TestClass3.class)));

    assertNotNull(type);
    assertTrue(type instanceof AnnotatedParameterizedType);
    AnnotatedParameterizedType result = (AnnotatedParameterizedType) type;

    assertSame(((ParameterizedType) result.getType()).getRawType(), DoubleParameters.class);
    assertSame(result.getAnnotatedActualTypeArguments()[0].getType(), OkClass.class);
    assertSame(result.getAnnotatedActualTypeArguments()[1].getType(), DoubleParameters.class);
  }
}
