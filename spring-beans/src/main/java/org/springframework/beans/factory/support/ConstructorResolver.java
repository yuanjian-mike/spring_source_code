/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.beans.ConstructorProperties;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.CollectionFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 * <p>Performs constructor resolution through argument matching.
 * 工厂方式的委托类
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 * @since 2.0
 */
class ConstructorResolver {

	private static final Object[] EMPTY_ARGS = new Object[0];

	/**
	 * Marker for autowired arguments in a cached argument array, to be later replaced
	 * by a {@linkplain #resolveAutowiredArgument resolved autowired argument}.
	 */
	private static final Object autowiredArgumentMarker = new Object();

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<>("Current injection point");


	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final Log logger;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 *
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.logger = beanFactory.getLogger();
	}


	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * <p>
	 * 构造方法进行实例化
	 *
	 * @param beanName     the name of the bean
	 * @param mbd          the merged bean definition for the bean
	 * @param chosenCtors  chosen candidate constructors (or {@code null} if none)
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 *                     or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd,
										   @Nullable Constructor<?>[] chosenCtors, @Nullable Object[] explicitArgs) {

		// 定义bean包装类
		BeanWrapperImpl bw = new BeanWrapperImpl();
		//忽略可以不看，设置类型转换器，注册自定义编辑器
		this.beanFactory.initBeanWrapper(bw);

		// 最终用于实例化的构造函数
		Constructor<?> constructorToUse = null;
		// 最终用于实例化的参数Holder
		ArgumentsHolder argsHolderToUse = null;
		// 最终用于实例化的构造函数参数
		Object[] argsToUse = null;

		// 1.解析出要用于实例化的构造函数参数
		if (explicitArgs != null) {
			// 1.1 如果explicitArgs不为空，则构造函数的参数直接使用explicitArgs
			// 通过getBean方法调用时，显示指定了参数，则explicitArgs就不为null
			argsToUse = explicitArgs;
		} else {
			// 1.2 尝试从缓存中获取已经解析过的构造函数参数
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				// 1.2.1 拿到缓存中已解析的构造函数或工厂方法
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				// 1.2.2 如果constructorToUse不为空 && mbd标记了构造函数参数已解析
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					// 1.2.3 从缓存中获取已解析的构造函数参数
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						// 1.2.4 如果resolvedConstructorArguments为空，则从缓存中获取准备用于解析的构造函数参数，
						// constructorArgumentsResolved为true时，resolvedConstructorArguments和
						// preparedConstructorArguments必然有一个缓存了构造函数的参数
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				// 1.2.5 如果argsToResolve不为空，则对构造函数参数进行解析，
				// 如给定方法的构造函数 A(int,int)则通过此方法后就会把配置中的("1","1")转换为(1,1)
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve, true);
			}
		}

		//如果构造函数没有被缓存
		if (constructorToUse == null || argsToUse == null) {
			// 2.确认构造函数的候选者
			// Take specified constructors, if any.

			// 2.1 如果入参chosenCtors不为空，则将chosenCtors的构造函数作为候选者
			Constructor<?>[] candidates = chosenCtors;
			//如果传过来的没有构造函数
			if (candidates == null) {
				//获取BeanClass
				Class<?> beanClass = mbd.getBeanClass();
				try {
					// 2.2 如果入参chosenCtors为空，则获取beanClass的构造函数
					// （mbd是否允许访问非公共构造函数和方法 ? 所有声明的构造函数：公共构造函数）
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				} catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
									"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}

			//mbd.hasConstructorArgumentValues()这个是false的，因为是@Autowired的构造函数，不是<constructor-arg>标签
			if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				Constructor<?> uniqueCandidate = candidates[0];
				//如果是无参构造函数
				if (uniqueCandidate.getParameterCount() == 0) {
					synchronized (mbd.constructorArgumentLock) {
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					//直接创建实例并返回
					bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}
			//3. 通过配置文件获取
			//需要解析参数
			// 3.1 检查是否需要自动装配：chosenCtors不为空 || autowireMode为AUTOWIRE_CONSTRUCTOR
			// 例子：当chosenCtors不为空时，代表有构造函数通过@Autowire修饰，因此需要自动装配
			// Need to resolve the constructor.
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			ConstructorArgumentValues resolvedValues = null;

			// 构造函数参数个数
			int minNrOfArgs;
			if (explicitArgs != null) {
				// 3.2 explicitArgs不为空，则使用explicitArgs的length作为minNrOfArgs的值
				minNrOfArgs = explicitArgs.length;
			} else {
				// 3.3 获得mbd的构造函数的参数值（indexedArgumentValues：带index的参数值；genericArgumentValues：通用的参数值）
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				// 3.4 创建ConstructorArgumentValues对象resolvedValues，用于承载解析后的构造函数参数的值
				resolvedValues = new ConstructorArgumentValues();
				// 3.5 解析mbd的构造函数的参数，并返回参数个数
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				// 注：这边解析mbd中的构造函数参数值，主要是处理我们通过xml方式定义的构造函数注入的参数，
				// 但是如果我们是通过@Autowire注解直接修饰构造函数，则mbd是没有这些参数值的
			}

			// 3.6 对给定的构造函数排序：先按方法修饰符排序：public排非public前面，再按构造函数参数个数排序：参数多的排前面
			AutowireUtils.sortConstructors(candidates);
			// 最小匹配权重，权重越小，越接近我们要找的目标构造函数
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Constructor<?>> ambiguousConstructors = null;
			LinkedList<UnsatisfiedDependencyException> causes = null;

			// 4.遍历所有构造函数候选者，找出符合条件的构造函数
			for (Constructor<?> candidate : candidates) {
				//4.1 获取到构造函数参数的数量
				int parameterCount = candidate.getParameterCount();
				if (constructorToUse != null && argsToUse != null && argsToUse.length > parameterCount) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					// 4.2 如果已经找到满足的构造函数 && 目标构造函数需要的参数个数大于当前遍历的构造函数的参数个数则终止，
					// 因为遍历的构造函数已经排过序，后面不会有更合适的候选者了
					break;
				}
				if (parameterCount < minNrOfArgs) {
					// 4.3 如果当前遍历到的构造函数的参数个数小于我们所需的参数个数，则直接跳过该构造函数
					continue;
				}

				ArgumentsHolder argsHolder;
				// 4.4 拿到当前遍历的构造函数的参数类型数组
				Class<?>[] paramTypes = candidate.getParameterTypes();
				if (resolvedValues != null) {
					// 存在参数则根据参数值来匹配参数类型
					try {
						// 4.5 resolvedValues不为空，
						// 4.5.1 获取当前遍历的构造函数的参数名称
						// 4.5.1.1 解析使用ConstructorProperties注解的构造函数参数
						String[] paramNames = ConstructorPropertiesChecker.evaluate(candidate, parameterCount);
						if (paramNames == null) {
							// 4.5.1.2 获取参数名称解析器
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								// 4.5.1.3 使用参数名称解析器获取当前遍历的构造函数的参数名称
								paramNames = pnd.getParameterNames(candidate);
							}
						}
						// 4.5.2 创建一个参数数组以调用构造函数或工厂方法，
						// 主要是通过参数类型和参数名解析构造函数或工厂方法所需的参数（如果参数是其他bean，则会解析依赖的bean）
						//获取到参数的值，建议不要看，比较深，主流程弄懂后再去细细打磨
						//获取构造参数并实例化参数
						argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
								getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
					} catch (UnsatisfiedDependencyException ex) {
						// 4.5.3 参数匹配失败，则抛出异常
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						// Swallow and try next constructor.
						if (causes == null) {
							causes = new LinkedList<>();
						}
						causes.add(ex);
						continue;
					}
				} else {
					// 4.6 resolvedValues为空，则explicitArgs不为空，即给出了显式参数
					// Explicit arguments given -> arguments length must match exactly.
					// 4.6.1 如果当前遍历的构造函数参数个数与explicitArgs长度不相同，则跳过该构造函数
					if (parameterCount != explicitArgs.length) {
						continue;
					}
					// 4.6.2 使用显式给出的参数构造ArgumentsHolder
					argsHolder = new ArgumentsHolder(explicitArgs);
				}

				// 4.7 根据mbd的解析构造函数模式（true: 宽松模式(默认)，false：严格模式），
				// 将argsHolder的参数和paramTypes进行比较，计算paramTypes的类型差异权重值
				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				// 4.8 类型差异权重值越小,则说明构造函数越匹配，则选择此构造函数
				if (typeDiffWeight < minTypeDiffWeight) {
					// 将要使用的参数都替换成差异权重值更小的
					constructorToUse = candidate;
					argsHolderToUse = argsHolder;
					argsToUse = argsHolder.arguments;
					minTypeDiffWeight = typeDiffWeight;
					// 如果出现权重值更小的候选者，则将ambiguousConstructors清空，允许之前存在权重值相同的候选者
					ambiguousConstructors = null;
				}
				// 4.9 如果存在两个候选者的权重值相同，并且是当前遍历过权重值最小的
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					// 将这两个候选者都添加到ambiguousConstructors
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<>();
						ambiguousConstructors.add(constructorToUse);
					}
					ambiguousConstructors.add(candidate);
				}
			}

			// 5.如果最终没有找到匹配的构造函数，则进行异常处理
			if (constructorToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor " +
								"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
			} else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				// 6.如果找到了匹配的构造函数，但是存在多个（ambiguousConstructors不为空） && 解析构造函数的模式为严格模式，则抛出异常
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found in bean '" + beanName + "' " +
								"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
								ambiguousConstructors);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				// 7.将解析的构造函数和参数放到缓存
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		Assert.state(argsToUse != null, "Unresolved constructor arguments");
		// 8.根据实例化策略以及得到的构造函数及构造函数参数实例化bean
		//并将构造的实例加入BeanWrapper中，并返回
		bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
		return bw;
	}

	private Object instantiate(
			String beanName, RootBeanDefinition mbd, Constructor<?> constructorToUse, Object[] argsToUse) {

		try {
			InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
								strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse),
						this.beanFactory.getAccessControlContext());
			} else {
				return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
			}
		} catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via constructor failed", ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 *
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			isStatic = false;
		} else {
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		Assert.state(factoryClass != null, "Unresolvable factory class");
		factoryClass = ClassUtils.getUserClass(factoryClass);

		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				} else if (isParamMismatch(uniqueCandidate, candidate)) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		mbd.factoryMethodToIntrospect = uniqueCandidate;
	}

	private boolean isParamMismatch(Method uniqueCandidate, Method candidate) {
		int uniqueCandidateParameterCount = uniqueCandidate.getParameterCount();
		int candidateParameterCount = candidate.getParameterCount();
		return (uniqueCandidateParameterCount != candidateParameterCount ||
				!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes()));
	}

	/**
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 */
	private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
		if (System.getSecurityManager() != null) {
			return AccessController.doPrivileged((PrivilegedAction<Method[]>) () ->
					(mbd.isNonPublicAccessAllowed() ?
							ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods()));
		} else {
			return (mbd.isNonPublicAccessAllowed() ?
					ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * 通过工厂委托类进行实例化bean
	 *
	 * @param beanName     the name of the bean
	 * @param mbd          the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 *                     method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {
		//1. 创建BeanWrapper的实现类
		BeanWrapperImpl bw = new BeanWrapperImpl();
		//2. 初始化 BeanWrapperImpl
		// 向BeanWrapper对象中添加 ConversionService 对象和属性编辑器 PropertyEditor 对象
		this.beanFactory.initBeanWrapper(bw);

		Object factoryBean;
		Class<?> factoryClass;
		boolean isStatic;
		//获取factoryBean的name
		String factoryBeanName = mbd.getFactoryBeanName();

		/**
		 * 如果存在factoryBeanName
		 *   <bean class="com.xiangxue.jack.bean.PropertyClass" id="propertyClass"
		 *    factory-bean="factoryBean" factory-method="factoryMethod"/>
		 *
		 *    需要先实例化factoryBean然后才能调用factoryMethod
		 */
		//3. 判断factoryBeanName不为空
		if (factoryBeanName != null) {
			//3.1 如果factoryBean和beanName一样直接报错
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			//4. 获取工厂bean的实例
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
				throw new ImplicitlyAppearedSingletonException();
			}
			//5. 获取工厂bean的class
			factoryClass = factoryBean.getClass();
			//说明这不是一个静态方法
			isStatic = false;
		} else {
			/**
			 * 如果不存在factoryBeanName
			 *  <bean class="com.xiangxue.jack.bean.PropertyClass" id="propertyClass"
			 *   factory-method="factoryMethod"/>
			 *
			 *   factoryMethod是一个静态方法，不需要先实例化factoryBean就可以调用factoryMethod
			 */
			// It's a static factory method on the bean class.
			//6.  工厂名为空，则其可能是一个静态工厂
			// 静态工厂创建bean，必须要提供工厂的全类名
			//如果BeanDefinition没有beanClass 直接报错
			if (!mbd.hasBeanClass()) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			//7. 设置静态工厂类的一下属性
			//7.1 设置factoryBean为null
			factoryBean = null;
			//7.2 设置factoryClass为BeanDefinition的class
			factoryClass = mbd.getBeanClass();
			//说明这是一个静态方法
			isStatic = true;
		}

		// 工厂方法
		Method factoryMethodToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		// 参数
		Object[] argsToUse = null;

		// 工厂方法的参数
		// 如果指定了构造参数则直接使用
		// 在调用 getBean 方法的时候指定了方法参数
		if (explicitArgs != null) {
			//参数赋值
			argsToUse = explicitArgs;
		} else {
			// 没有指定，则尝试从配置文件中解析
			Object[] argsToResolve = null;
			// 首先尝试从缓存中获取
			synchronized (mbd.constructorArgumentLock) {
				// 获取缓存中的构造函数或者工厂方法
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					// 获取缓存中的构造参数
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						// 获取缓存中的构造函数参数的包可见字段
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			// 缓存中存在,则解析存储在 BeanDefinition 中的参数
			// 如给定方法的构造函数 A(int ,int )，则通过此方法后就会把配置文件中的("1","1")转换为 (1,1)
			// 缓存中的值可能是原始值也有可能是最终值
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve, true);
			}
		}

		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			// 获取工厂方法的类全名称
			factoryClass = ClassUtils.getUserClass(factoryClass);

			List<Method> candidates = null;
			// 如果工厂方法是唯一的
			if (mbd.isFactoryMethodUnique) {
				if (factoryMethodToUse == null) {
					//将工厂方法转换成内省方法
					factoryMethodToUse = mbd.getResolvedFactoryMethod();
				}
				if (factoryMethodToUse != null) {
					//获取方法的集合
					candidates = Collections.singletonList(factoryMethodToUse);
				}
			}
			//如果candidates为null说明
			if (candidates == null) {
				candidates = new ArrayList<>();
				// 获取所有待定方法
				Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
				// 检索所有方法，这里是对方法进行过滤
				for (Method candidate : rawCandidates) {
					// 如果有static 且为工厂方法，则添加到 candidateSet 中
					if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
						candidates.add(candidate);
					}
				}
			}

			if (candidates.size() == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				Method uniqueCandidate = candidates.get(0);
				if (uniqueCandidate.getParameterCount() == 0) {
					mbd.factoryMethodToIntrospect = uniqueCandidate;
					synchronized (mbd.constructorArgumentLock) {
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			// 排序构造函数
			// public 构造函数优先参数数量降序，非public 构造函数参数数量降序
			if (candidates.size() > 1) {  // explicitly skip immutable singletonList
				candidates.sort(AutowireUtils.EXECUTABLE_COMPARATOR);
			}

			// 用于承载解析后的构造函数参数的值
			ConstructorArgumentValues resolvedValues = null;
			boolean autowiring = (mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Method> ambiguousFactoryMethods = null;

			int minNrOfArgs;
			// getBean()传递了参数
			if (explicitArgs != null) {
				//获取参数的长度
				minNrOfArgs = explicitArgs.length;
			} else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				// getBean() 没有传递参数，则需要解析保存在 BeanDefinition 构造函数中指定的参数
				if (mbd.hasConstructorArgumentValues()) {
					// 构造函数的参数
					ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
					resolvedValues = new ConstructorArgumentValues();
					// 解析构造函数的参数
					// 将该 bean 的构造函数参数解析为 resolvedValues 对象，其中会涉及到其他 bean
					minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				} else {
					minNrOfArgs = 0;
				}
			}

			LinkedList<UnsatisfiedDependencyException> causes = null;

			for (Method candidate : candidates) {
				// 方法体的参数
				int parameterCount = candidate.getParameterCount();
				if (parameterCount >= minNrOfArgs) {
					// 保存参数的对象
					ArgumentsHolder argsHolder;
					//获取参数类型
					Class<?>[] paramTypes = candidate.getParameterTypes();
					// getBean()传递了参数
					if (explicitArgs != null) {
						// Explicit arguments given -> arguments length must match exactly.
						// 显示给定参数，参数长度必须完全匹配
						if (paramTypes.length != explicitArgs.length) {
							continue;
						}
						// 根据参数创建参数持有者
						argsHolder = new ArgumentsHolder(explicitArgs);
					} else {
						// 为提供参数，解析构造参数
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							String[] paramNames = null;
							// 获取 ParameterNameDiscoverer 对象
							// ParameterNameDiscoverer 是用于解析方法和构造函数的参数名称的接口，为参数名称探测器
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								// 获取指定构造函数的参数名称
								paramNames = pnd.getParameterNames(candidate);
							}
							// 在已经解析的构造函数参数值的情况下，创建一个参数持有者对象
							argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
									paramTypes, paramNames, candidate, autowiring, candidates.size() == 1);
						} catch (UnsatisfiedDependencyException ex) {
							if (logger.isTraceEnabled()) {
								logger.trace("Ignoring factory method [" + candidate + "] of bean '" + beanName + "': " + ex);
							}
							// Swallow and try next overloaded factory method.
							if (causes == null) {
								causes = new LinkedList<>();
							}
							causes.add(ex);
							continue;
						}
					}

					// isLenientConstructorResolution 判断解析构造函数的时候是否以宽松模式还是严格模式
					// 严格模式：解析构造函数时，必须所有的都需要匹配，否则抛出异常
					// 宽松模式：使用具有"最接近的模式"进行匹配
					// typeDiffWeight：类型差异权重
					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					// 代表最接近的类型匹配，则选择作为构造函数
					if (typeDiffWeight < minTypeDiffWeight) {
						factoryMethodToUse = candidate;
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						minTypeDiffWeight = typeDiffWeight;
						ambiguousFactoryMethods = null;
					}
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).
					// 如果具有相同参数数量的方法具有相同的类型差异权重，则收集此类型选项
					// 但是，仅在非宽松构造函数解析模式下执行该检查，并显式忽略重写方法（具有相同的参数签名）
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
							!mbd.isLenientConstructorResolution() &&
							paramTypes.length == factoryMethodToUse.getParameterCount() &&
							!Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						// 查找到多个可匹配的方法
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}

			// 没有可执行的工厂方法，抛出异常
			if (factoryMethodToUse == null || argsToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				List<String> argTypes = new ArrayList<>(minNrOfArgs);
				if (explicitArgs != null) {
					for (Object arg : explicitArgs) {
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				} else if (resolvedValues != null) {
					Set<ValueHolder> valueHolders = new LinkedHashSet<>(resolvedValues.getArgumentCount());
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					for (ValueHolder value : valueHolders) {
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						argTypes.add(argType);
					}
				}
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found: " +
								(mbd.getFactoryBeanName() != null ?
										"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
								"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
								"Check that a method with the specified name " +
								(minNrOfArgs > 0 ? "and arguments " : "") +
								"exists and that it is " +
								(isStatic ? "static" : "non-static") + ".");
			} else if (void.class == factoryMethodToUse.getReturnType()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() +
								"': needs to have a non-void return type!");
			} else if (ambiguousFactoryMethods != null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found in bean '" + beanName + "' " +
								"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
								ambiguousFactoryMethods);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				mbd.factoryMethodToIntrospect = factoryMethodToUse;
				// 将解析的构造函数加入缓存
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}
		//封装并实例化工厂bean
		bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
		return bw;
	}

	/**
	 * 实例化工厂bean
	 *
	 * @param beanName
	 * @param mbd
	 * @param factoryBean   工厂bean
	 * @param factoryMethod 工厂方法
	 * @param args          参数
	 * @return
	 */
	private Object instantiate(String beanName, RootBeanDefinition mbd,
							   @Nullable Object factoryBean, Method factoryMethod, Object[] args) {

		try {
			//如果有安全管理器
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
								this.beanFactory.getInstantiationStrategy().instantiate(
										mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args),
						this.beanFactory.getAccessControlContext());
			} else {
				return this.beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
			}
		} catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via factory method failed", ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 * <p>This method is also used for handling invocations of static factory methods.
	 * <p>
	 * 构造方法参数解析
	 */
	private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
											ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {
		// 1.构建bean定义值解析器
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

		// 2.minNrOfArgs初始化为indexedArgumentValues和genericArgumentValues的的参数个数总和
		int minNrOfArgs = cargs.getArgumentCount();

		// 3.遍历解析带index的参数值
		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			int index = entry.getKey();
			if (index < 0) {
				// index从0开始，不允许小于0
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			// 3.1 如果index大于minNrOfArgs，则修改minNrOfArgs
			if (index > minNrOfArgs) {
				// index是从0开始，并且是有序递增的，所以当有参数的index=5时，代表该方法至少有6个参数
				minNrOfArgs = index + 1;
			}
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			// 3.2 解析参数值
			if (valueHolder.isConverted()) {
				// 3.2.1 如果参数值已经转换过，则直接将index和valueHolder添加到resolvedValues的indexedArgumentValues属性
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			} else {
				// 3.2.2 如果值还未转换过，则先进行转换
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				// 3.2.3 使用转换后的resolvedValue构建新的ValueHolder
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				// 3.2.4 将转换前的valueHolder保存到新的ValueHolder的source属性
				resolvedValueHolder.setSource(valueHolder);
				// 3.2.5 将index和新的ValueHolder添加到resolvedValues的indexedArgumentValues属性
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}

		// 4.遍历解析通用参数值（不带index）
		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			if (valueHolder.isConverted()) {
				// 4.1 如果参数值已经转换过，则直接将valueHolder添加到resolvedValues的genericArgumentValues属性
				resolvedValues.addGenericArgumentValue(valueHolder);
			} else {
				// 4.2 如果值还未转换过，则先进行转换
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				// 4.3 使用转换后的resolvedValue构建新的ValueHolder
				ConstructorArgumentValues.ValueHolder resolvedValueHolder = new ConstructorArgumentValues.ValueHolder(
						resolvedValue, valueHolder.getType(), valueHolder.getName());
				// 4.4 将转换前的valueHolder保存到新的ValueHolder的source属性
				resolvedValueHolder.setSource(valueHolder);
				// 4.5 将新的ValueHolder添加到resolvedValues的genericArgumentValues属性
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}
		// 5.返回构造函数参数的个数
		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 * 创建参数数组以调用构造函数或工厂方法，
	 * 给定解析的构造函数参数值。
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, @Nullable ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class<?>[] paramTypes, @Nullable String[] paramNames, Executable executable,
			boolean autowiring, boolean fallback) throws UnsatisfiedDependencyException {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		// 获取类型转换器
		TypeConverter converter = (customConverter != null ? customConverter : bw);

		// 新建一个ArgumentsHolder来存放匹配到的参数
		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);

		// 1.遍历参数类型数组
		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			// 拿到当前遍历的参数类型
			Class<?> paramType = paramTypes[paramIndex];
			// 拿到当前遍历的参数名
			String paramName = (paramNames != null ? paramNames[paramIndex] : "");
			// Try to find matching constructor argument value, either indexed or generic.
			// 2.查找当前遍历的参数，是否在mdb对应的bean的构造函数参数中存在index、类型和名称匹配的
			ConstructorArgumentValues.ValueHolder valueHolder = null;
			if (resolvedValues != null) {
				valueHolder = resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
				// If we couldn't find a direct match and are not supposed to autowire,
				// let's try the next generic, untyped argument value as fallback:
				// it could match after type conversion (for example, String -> int).
				// 3.如果我们找不到直接匹配并且不应该自动装配，那么让我们尝试下一个通用的无类型参数值作为降级方法：它可以在类型转换后匹配（例如，String - > int）。
				if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
					valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
				}
			}
			if (valueHolder != null) {
				// 4.valueHolder不为空，存在匹配的参数
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				// 将valueHolder添加到usedValueHolders
				usedValueHolders.add(valueHolder);
				// 原始属性值
				Object originalValue = valueHolder.getValue();
				// 转换后的属性值
				Object convertedValue;
				if (valueHolder.isConverted()) {
					// 4.1 如果valueHolder已经转换过
					// 4.1.1 则直接获取转换后的值
					convertedValue = valueHolder.getConvertedValue();
					// 4.1.2 将convertedValue作为args在paramIndex位置的预备参数
					args.preparedArguments[paramIndex] = convertedValue;
				} else {
					// 4.2 如果valueHolder还未转换过
					// 4.2.1 将方法（此处为构造函数）和参数索引封装成MethodParameter(MethodParameter是封装方法和参数索引的工具类)
					MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
					try {
						// 4.2.2 将原始值转换为paramType类型的值（如果类型无法转，抛出TypeMismatchException）
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
					} catch (TypeMismatchException ex) {
						// 4.2.3 如果类型转换失败，则抛出异常
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
								"Could not convert argument value of type [" +
										ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
										"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
					// 4.2.4 拿到原始参数值
					Object sourceHolder = valueHolder.getSource();
					// 4.2.5 如果sourceHolder是ConstructorArgumentValues.ValueHolder类型的拿到原始的ValueHolder
					if (sourceHolder instanceof ConstructorArgumentValues.ValueHolder) {
						Object sourceValue = ((ConstructorArgumentValues.ValueHolder) sourceHolder).getValue();
						// 4.2.6 args标记为需要解析
						args.resolveNecessary = true;
						// 4.2.7 将convertedValue作为args在paramIndex位置的预备参数
						args.preparedArguments[paramIndex] = sourceValue;
					}
				}
				// 4.3 将convertedValue作为args在paramIndex位置的参数
				args.arguments[paramIndex] = convertedValue;
				// 4.4 将originalValue作为args在paramIndex位置的原始参数
				args.rawArguments[paramIndex] = originalValue;
			} else {
				// 5.valueHolder为空，不存在匹配的参数
				// 5.1 将方法（此处为构造函数）和参数索引封装成MethodParameter
				MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				// 5.2 找不到明确的匹配，并且不是自动装配，则抛出异常
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Ambiguous argument values for parameter of type [" + paramType.getName() +
									"] - did you specify the correct bean references as arguments?");
				}
				try {
					// 5.3 如果是自动装配，则调用用于解析自动装配参数的方法，返回的结果为依赖的bean实例对象
					// 例如：@Autowire修饰构造函数，自动注入构造函数中的参数bean就是在这边处理
					Object autowiredArgument = resolveAutowiredArgument(
							methodParam, beanName, autowiredBeanNames, converter, fallback);
					// 5.4 将通过自动装配解析出来的参数赋值给args
					args.rawArguments[paramIndex] = autowiredArgument;
					args.arguments[paramIndex] = autowiredArgument;
					args.preparedArguments[paramIndex] = autowiredArgumentMarker;
					args.resolveNecessary = true;
				} catch (BeansException ex) {
					// 5.5 如果自动装配解析失败，则会抛出异常
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
				}
			}
		}
		// 6.如果依赖了其他的bean，则注册依赖关系
		for (String autowiredBeanName : autowiredBeanNames) {
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (logger.isDebugEnabled()) {
				logger.debug("Autowiring by type from bean name '" + beanName +
						"' via " + (executable instanceof Constructor ? "constructor" : "factory method") +
						" to bean named '" + autowiredBeanName + "'");
			}
		}

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 */
	private Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
											  Executable executable, Object[] argsToResolve, boolean fallback) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		Class<?>[] paramTypes = executable.getParameterTypes();

		Object[] resolvedArgs = new Object[argsToResolve.length];
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			Object argValue = argsToResolve[argIndex];
			MethodParameter methodParam = MethodParameter.forExecutable(executable, argIndex);
			if (argValue == autowiredArgumentMarker) {
				argValue = resolveAutowiredArgument(methodParam, beanName, null, converter, fallback);
			} else if (argValue instanceof BeanMetadataElement) {
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
			} else if (argValue instanceof String) {
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
			}
			Class<?> paramType = paramTypes[argIndex];
			try {
				resolvedArgs[argIndex] = converter.convertIfNecessary(argValue, paramType, methodParam);
			} catch (TypeMismatchException ex) {
				throw new UnsatisfiedDependencyException(
						mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
						"Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
								"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
			}
		}
		return resolvedArgs;
	}

	protected Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
		Class<?> declaringClass = constructor.getDeclaringClass();
		Class<?> userClass = ClassUtils.getUserClass(declaringClass);
		if (userClass != declaringClass) {
			try {
				return userClass.getDeclaredConstructor(constructor.getParameterTypes());
			} catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
	}

	/**
	 * Template method for resolving the specified argument which is supposed to be autowired.
	 * 用于解析应自动连接的指定参数的模板方法。
	 */
	@Nullable
	protected Object resolveAutowiredArgument(MethodParameter param, String beanName,
											  @Nullable Set<String> autowiredBeanNames, TypeConverter typeConverter, boolean fallback) {
		// 1.如果参数类型为InjectionPoint
		Class<?> paramType = param.getParameterType();
		if (InjectionPoint.class.isAssignableFrom(paramType)) {
			// 1.1 拿到当前的InjectionPoint（存储了当前正在解析依赖的方法参数信息，DependencyDescriptor）
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			if (injectionPoint == null) {
				// 1.2 当前injectionPoint为空，则抛出异常：目前没有可用的InjectionPoint
				throw new IllegalStateException("No current InjectionPoint available for " + param);
			}
			// 1.3 返回当前的InjectionPoint
			return injectionPoint;
		}
		try {
			// 2.解析指定依赖，DependencyDescriptor：将MethodParameter的方法参数索引信息封装成DependencyDescriptor
			return this.beanFactory.resolveDependency(
					new DependencyDescriptor(param, true), beanName, autowiredBeanNames, typeConverter);
		} catch (NoUniqueBeanDefinitionException ex) {
			throw ex;
		} catch (NoSuchBeanDefinitionException ex) {
			if (fallback) {
				// Single constructor or factory method -> let's return an empty array/collection
				// for e.g. a vararg or a non-null List/Set/Map parameter.
				if (paramType.isArray()) {
					return Array.newInstance(paramType.getComponentType(), 0);
				} else if (CollectionFactory.isApproximableCollectionType(paramType)) {
					return CollectionFactory.createCollection(paramType, 0);
				} else if (CollectionFactory.isApproximableMapType(paramType)) {
					return CollectionFactory.createMap(paramType, 0);
				}
			}
			throw ex;
		}
	}

	static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		} else {
			currentInjectionPoint.remove();
		}
		return old;
	}


	/**
	 * Private inner class for holding argument combinations.
	 */
	private static class ArgumentsHolder {

		public final Object[] rawArguments;

		public final Object[] arguments;

		public final Object[] preparedArguments;

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			return Math.min(rawTypeDiffWeight, typeDiffWeight);
		}

		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			return Integer.MAX_VALUE - 1024;
		}

		public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
			synchronized (mbd.constructorArgumentLock) {
				// 将构造函数或工厂方法放到resolvedConstructorOrFactoryMethod缓存
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				// constructorArgumentsResolved标记为已解析
				mbd.constructorArgumentsResolved = true;
				if (this.resolveNecessary) {
					// 如果参数需要解析，则将preparedArguments放到preparedConstructorArguments缓存
					mbd.preparedConstructorArguments = this.preparedArguments;
				} else {
					// 如果参数不需要解析，则将arguments放到resolvedConstructorArguments缓存
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Delegate for checking Java 6's {@link ConstructorProperties} annotation.
	 */
	private static class ConstructorPropertiesChecker {

		@Nullable
		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				String[] names = cp.value();
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			} else {
				return null;
			}
		}
	}

}
