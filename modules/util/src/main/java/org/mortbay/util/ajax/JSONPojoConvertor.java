//========================================================================
//Copyright 2009 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package org.mortbay.util.ajax;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.mortbay.log.Log;
import org.mortbay.util.ajax.JSON.Output;
/* ------------------------------------------------------------ */
/**
 * Converts POJOs to JSON and vice versa.
 * The key difference:
 *  - returns the actual object from Convertor.fromJSON (JSONObjectConverter returns a Map)
 *  - the getters/setters are resolved at initialization (JSONObjectConverter resolves it at runtime)
 *  - correctly sets the number fields
 * 
 * @author dyu
 *
 */
public class JSONPojoConvertor implements JSON.Convertor
{
    
    private static final Object[] __getterArg = new Object[]{};
    private static final Map/*<Class<?>, NumberType>*/  __numberTypes = new HashMap/*<Class<?>, NumberType>*/();
    
    public static NumberType getNumberType(Class clazz)
    {
        return (NumberType)__numberTypes.get(clazz);
    }
    
    protected boolean _fromJSON;
    protected Class _pojoClass;
    protected Map/*<String,Method>*/ _getters = new HashMap/*<String,Method>*/();
    protected Map/*<String,Setter>*/ _setters = new HashMap/*<String,Setter>*/();
    protected Set/*<String>*/ _excluded;
    
    public JSONPojoConvertor(Class pojoClass)
    {
        this(pojoClass, (Set)null, true);
    }
    
    public JSONPojoConvertor(Class pojoClass, String[] excluded)
    {
        this(pojoClass, new HashSet(Arrays.asList(excluded)), true);
    }
    
    public JSONPojoConvertor(Class pojoClass, Set excluded)
    {
        this(pojoClass, excluded, true);
    }
    
    public JSONPojoConvertor(Class pojoClass, Set excluded, boolean fromJSON)
    {
        _pojoClass = pojoClass;
        _excluded = excluded;
        _fromJSON = fromJSON;
        init();
    }    
    
    public JSONPojoConvertor(Class pojoClass, boolean fromJSON)
    {
        this(pojoClass, (Set)null, fromJSON);
    }
    
    /* ------------------------------------------------------------ */
    protected void init()
    {
        Method[] methods = _pojoClass.getMethods();
        for (int i=0;i<methods.length;i++)
        {
            Method m=methods[i];
            if (!Modifier.isStatic(m.getModifiers()) && m.getDeclaringClass()!=Object.class)
            {
                String name=m.getName();
                switch(m.getParameterTypes().length)
                {
                    case 0:
                        
                        if(m.getReturnType()!=null)
                        {
                            if (name.startsWith("is"))
                                name=name.substring(2,3).toLowerCase()+name.substring(3);
                            else if (name.startsWith("get"))
                                name=name.substring(3,4).toLowerCase()+name.substring(4);
                            else 
                                break;
                            if(includeField(name, m))
                                _getters.put(name, m);
                        }
                        break;
                    case 1:
                        if (name.startsWith("set"))
                        {
                            name=name.substring(3,4).toLowerCase()+name.substring(4);
                            if(includeField(name, m))
                                _setters.put(name, new Setter(name, m));
                        }
                        break;                
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected boolean includeField(String name, Method m)
    {
        return _excluded==null || !_excluded.contains(name);
    }
    
    /* ------------------------------------------------------------ */
    protected int getExcludedCount()
    {
        return _excluded==null ? 0 : _excluded.size();
    }

    /* ------------------------------------------------------------ */
    public Object fromJSON(Map object)
    {        
        Object obj = null;
        try
        {
            obj = _pojoClass.newInstance();
        }
        catch(Exception e)
        {
            // TODO return Map instead?
            throw new RuntimeException(e);
        }
        
        for(Iterator iterator = object.entrySet().iterator(); iterator.hasNext();)
        {
            Map.Entry entry = (Map.Entry)iterator.next();
            Setter setter = (Setter)_setters.get(entry.getKey());
            if(setter!=null)
            {
                try
                {
                    setter.invoke(obj, entry.getValue());                    
                }
                catch(Exception e)
                {
                    // TODO throw exception?
                    Log.warn("{} property '{}' not set. (errors)", _pojoClass.getName(), 
                            setter.getPropertyName());                    
                }
            }
        }
        
        return obj;
    }

    /* ------------------------------------------------------------ */
    public void toJSON(Object obj, Output out)
    {
        out.addClass(_pojoClass);
        for(Iterator iterator = _getters.entrySet().iterator(); iterator.hasNext();)
        {
            Map.Entry entry = (Map.Entry)iterator.next();
            try
            {
                out.add((String)entry.getKey(), ((Method)entry.getValue()).invoke(obj, 
                        __getterArg));                    
            }
            catch(Exception e)
            {
                // TODO throw exception?
                Log.warn("{} property '{}' excluded. (errors)", _pojoClass.getName(), 
                        entry.getKey());                
            }
        }        
    }
    
    public static class Setter
    {
        private String _propertyName;
        private Method _method;
        private NumberType _numberType;
        
        public Setter(String propertyName, Method method)
        {
            _propertyName = propertyName;
            _method = method;
            _numberType = (NumberType)__numberTypes.get(method.getParameterTypes()[0]);
        }
        
        public String getPropertyName()
        {
            return _propertyName;
        }
        
        public Method getMethod()
        {
            return _method;
        }
        
        public NumberType getNumberType()
        {
            return _numberType;
        }
        
        public boolean isPropertyNumber()
        {
            return _numberType!=null;
        }
        
        public void invoke(Object obj, Object value) throws IllegalArgumentException, 
            IllegalAccessException, InvocationTargetException
        {
            if(_numberType!=null && value instanceof Number)
                _method.invoke(obj, new Object[]{_numberType.getActualValue((Number)value)});
            else
                _method.invoke(obj, new Object[]{value});
        }
    }
    
    public interface NumberType
    {        
        public Object getActualValue(Number number);     
    }
    
    public static final NumberType SHORT = new NumberType()
    {
        public Object getActualValue(Number number)
        {            
            return new Short(number.shortValue());
        } 
    };

    public static final NumberType INTEGER = new NumberType()
    {
        public Object getActualValue(Number number)
        {            
            return new Integer(number.intValue());
        }
    };
    
    public static final NumberType FLOAT = new NumberType()
    {
        public Object getActualValue(Number number)
        {            
            return new Float(number.floatValue());
        }      
    };

    public static final NumberType LONG = new NumberType()
    {
        public Object getActualValue(Number number)
        {            
            return number instanceof Long ? number : new Long(number.longValue());
        }     
    };

    public static final NumberType DOUBLE = new NumberType()
    {
        public Object getActualValue(Number number)
        {            
            return number instanceof Double ? number : new Double(number.doubleValue());
        }       
    };

    static
    {
        __numberTypes.put(Short.class, SHORT);
        __numberTypes.put(Short.TYPE, SHORT);
        __numberTypes.put(Integer.class, INTEGER);
        __numberTypes.put(Integer.TYPE, INTEGER);
        __numberTypes.put(Long.class, LONG);
        __numberTypes.put(Long.TYPE, LONG);
        __numberTypes.put(Float.class, FLOAT);
        __numberTypes.put(Float.TYPE, FLOAT);
        __numberTypes.put(Double.class, DOUBLE);
        __numberTypes.put(Double.TYPE, DOUBLE);
    }
}
