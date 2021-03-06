package com.fasterxml.jackson.databind.introspect;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.ClassIntrospector.MixInResolver;
import com.fasterxml.jackson.databind.type.TypeBindings;
import com.fasterxml.jackson.databind.util.Annotations;
import com.fasterxml.jackson.databind.util.ClassUtil;

/**
 * Helper class that contains logic for resolving annotations to construct
 * {@link AnnotatedClass} instances.
 *
 * @since 2.9
 */
public class AnnotatedClassResolver
{
    private final MapperConfig<?> _config;
    private final AnnotationIntrospector _intr;
    private final MixInResolver _mixInResolver;
    private final TypeBindings _bindings;

    private final JavaType _type;
    private final Class<?> _class;
    private final Class<?> _primaryMixin;

    AnnotatedClassResolver(MapperConfig<?> config, JavaType type, MixInResolver r) {
        _config = config;
        _type = type;
        _class = type.getRawClass();
        _mixInResolver = r;
        _bindings = type.getBindings();
        _intr = config.isAnnotationProcessingEnabled()
                ? config.getAnnotationIntrospector() : null;
        _primaryMixin = _config.findMixInClassFor(_class);
    }

    AnnotatedClassResolver(MapperConfig<?> config, Class<?> cls, MixInResolver r) {
        _config = config;
        _type = null;
        _class = cls;
        _mixInResolver = r;
        _bindings = TypeBindings.emptyBindings();
        if (config == null) {
            _intr = null;
            _primaryMixin = null;
        } else {
            _intr = config.isAnnotationProcessingEnabled()
                    ? config.getAnnotationIntrospector() : null;
                _primaryMixin = _config.findMixInClassFor(_class);
        }
    }

    public static AnnotatedClass resolve(MapperConfig<?> config, JavaType forType) {
        return new AnnotatedClassResolver(config, forType, config).resolveFully();
    }

    public static AnnotatedClass resolve(MapperConfig<?> config, JavaType forType,
            MixInResolver r)
    {
        return new AnnotatedClassResolver(config, forType, r).resolveFully();
    }

    public static AnnotatedClass resolveWithoutSuperTypes(MapperConfig<?> config, JavaType forType,
            MixInResolver r)
    {
        return new AnnotatedClassResolver(config, forType, r).resolveWithoutSuperTypes();
    }

    public static AnnotatedClass resolveWithoutSuperTypes(MapperConfig<?> config, Class<?> forType) {
        return resolveWithoutSuperTypes(config, forType, config);
    }

    public static AnnotatedClass resolveWithoutSuperTypes(MapperConfig<?> config, Class<?> forType,
            MixInResolver r)
    {
        return new AnnotatedClassResolver(config, forType, r).resolveWithoutSuperTypes();
    }

    /**
     * Internal helper class used for resolving a small set of "primordial" types for which
     * we do not accept any annotation information or overrides. 
     */
    static AnnotatedClass createPrimordial(Class<?> raw) {
        Annotations noClassAnn = new AnnotationMap();
        List<JavaType> superTypes = Collections.emptyList();
        return new AnnotatedClass(null, raw, superTypes, null, noClassAnn,
                TypeBindings.emptyBindings(), null, null, null);
    }

    AnnotatedClass resolveFully() {
        List<JavaType> superTypes = ClassUtil.findSuperTypes(_type, null, false);
        Annotations classAnn = resolveClassAnnotations(superTypes);
        return new AnnotatedClass(_type, _class, superTypes, _primaryMixin, classAnn, _bindings,
                _intr, _mixInResolver, _config.getTypeFactory());

    }

    AnnotatedClass resolveWithoutSuperTypes() {
        List<JavaType> superTypes = Collections.<JavaType>emptyList();
        Annotations classAnn = resolveClassAnnotations(superTypes);
        return new AnnotatedClass(null, _class, superTypes, _primaryMixin, classAnn,
                _bindings, _intr, _config, _config.getTypeFactory());
    }

    /*
    /**********************************************************
    /* Class annotation resolution
    /**********************************************************
     */
    
    /**
     * Initialization method that will recursively collect Jackson
     * annotations for this class and all super classes and
     * interfaces.
     */
    private AnnotationMap resolveClassAnnotations(List<JavaType> superTypes)
    {
        // Should skip processing if annotation processing disabled
        if (_intr == null) {
            return new AnnotationMap();
        }
        AnnotationMap resolvedCA = new AnnotationMap();
        // add mix-in annotations first (overrides)
        if (_primaryMixin != null) {
            _addClassMixIns(resolvedCA, _class, _primaryMixin);
        }
        // first, annotations from the class itself:
        _addAnnotationsIfNotPresent(resolvedCA,
                ClassUtil.findClassAnnotations(_class));

        // and then from super types
        for (JavaType type : superTypes) {
            // and mix mix-in annotations in-between
            if (_mixInResolver != null) {
                Class<?> cls = type.getRawClass();
                _addClassMixIns(resolvedCA, cls,
                        _mixInResolver.findMixInClassFor(cls));
            }
            _addAnnotationsIfNotPresent(resolvedCA,
                    ClassUtil.findClassAnnotations(type.getRawClass()));
        }
        /* and finally... any annotations there might be for plain
         * old Object.class: separate because for all other purposes
         * it is just ignored (not included in super types)
         */
        // 12-Jul-2009, tatu: Should this be done for interfaces too?
        //  For now, yes, seems useful for some cases, and not harmful for any?
        if (_mixInResolver != null) {
            _addClassMixIns(resolvedCA, Object.class,
                    _mixInResolver.findMixInClassFor(Object.class));
        }
        return resolvedCA;
    }

    private void _addClassMixIns(AnnotationMap annotations,
            Class<?> target, Class<?> mixin)
    {
        if (mixin != null) {
            // Ok, first: annotations from mix-in class itself:
            _addAnnotationsIfNotPresent(annotations, ClassUtil.findClassAnnotations(mixin));
    
            // And then from its supertypes, if any. But note that we will only consider
            // super-types up until reaching the masked class (if found); this because
            // often mix-in class is a sub-class (for convenience reasons).
            // And if so, we absolutely must NOT include super types of masked class,
            // as that would inverse precedence of annotations.
            for (Class<?> parent : ClassUtil.findSuperClasses(mixin, target, false)) {
                _addAnnotationsIfNotPresent(annotations, ClassUtil.findClassAnnotations(parent));
            }
        }
    }

    private AnnotationMap _addAnnotationsIfNotPresent(AnnotationMap result, Annotation[] anns)
    {
        if (anns != null) {
            List<Annotation> fromBundles = null;
            for (Annotation ann : anns) { // first: direct annotations
                // note: we will NOT filter out non-Jackson anns any more
                boolean wasNotPresent = result.addIfNotPresent(ann);
                if (wasNotPresent && _intr.isAnnotationBundle(ann)) {
                    fromBundles = _addFromBundle(ann, fromBundles);
                }
            }
            if (fromBundles != null) { // and secondarily handle bundles, if any found: precedence important
                _addAnnotationsIfNotPresent(result,
                        fromBundles.toArray(new Annotation[fromBundles.size()]));
            }
        }
        return result;
    }

    private List<Annotation> _addFromBundle(Annotation bundle, List<Annotation> result)
    {
        for (Annotation a : ClassUtil.findClassAnnotations(bundle.annotationType())) {
            // minor optimization: by-pass 2 common JDK meta-annotations
            if ((a instanceof Target) || (a instanceof Retention)) {
                continue;
            }
            if (result == null) {
                result = new ArrayList<Annotation>();
            }
            result.add(a);
        }
        return result;
    }
}
