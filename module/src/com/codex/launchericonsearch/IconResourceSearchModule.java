package com.codex.launchericonsearch;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Xml;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Adds full icon-pack resource-name search to the launcher icon picker. */
public final class IconResourceSearchModule extends XposedModule {
    private static final String TARGET_PACKAGE = "com.android.launcher";
    private static final String FRAGMENT = "com.oplus.uxicon.ui.ui.UxChangeIconPanelFragment";
    private static final String PAGER_ADAPTER = "com.oplus.uxicon.ui.ui.ViewPagerAdapter";
    private static final String ICON_ADAPTER = "com.oplus.uxicon.ui.ui.ChooseIconAdapter";
    private static final String HELPER = "com.oplus.uxicon.ui.util.UxIconPackHelper";
    private static final String SEARCH_TAG = "codex_icon_resource_search";
    private static final String DESCRIPTOR_PREFIX = "com.oplus.uxicon.ui.util.d$";
    private static final String DESCRIPTOR_RESOURCE_FIELD = "a";
    private static final int ICONS_PER_PAGE = 28;
    private static final int MIN_PAGE = Integer.MIN_VALUE;
    private static final int SEARCH_BAR_HEIGHT_DP = 48;
    private static final int SEARCH_BAR_HORIZONTAL_MARGIN_DP = 16;
    private static final int SEARCH_BAR_VERTICAL_MARGIN_DP = 8;
    private static final int SEARCH_ICON_SIZE_DP = 20;
    private static final int SEARCH_ICON_START_DP = 12;
    private static final int SEARCH_CLEAR_SIZE_DP = 36;
    private static final int MAX_ICON_PACK_ITEMS = 100000;

    private static final Object LOCK = new Object();
    private static final Set<ClassLoader> INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private static final Map<Object, FragmentState> STATES =
            new WeakHashMap<Object, FragmentState>();
    private static final Map<Object, FragmentState> ADAPTER_OWNERS =
            new WeakHashMap<Object, FragmentState>();
    private static final Map<Object, LinkedHashMap<Object, Object>> NATIVE_DATA =
            new WeakHashMap<Object, LinkedHashMap<Object, Object>>();
    private static final Map<String, PackData> PACKS =
            new HashMap<String, PackData>();
    private static final ExecutorService SEARCH_EXECUTOR =
            Executors.newCachedThreadPool();

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;
        try {
            install(param.getDefaultClassLoader());
        } catch (Throwable t) {
            log(XposedInterface.PRIORITY_DEFAULT, "IconResourceSearch", "hook install failed", t);
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;
        try {
            install(param.getClassLoader());
        } catch (Throwable t) {
            log(XposedInterface.PRIORITY_DEFAULT, "IconResourceSearch", "hook install failed", t);
        }
    }

    private void install(final ClassLoader loader) throws Throwable {
        if (loader == null) return;
        synchronized (LOCK) {
            if (INSTALLED.contains(loader)) return;
            INSTALLED.add(loader);
        }
        try {
            final Class<?> fragmentClass = Class.forName(FRAGMENT, false, loader);
            final Class<?> iconAdapterClass = Class.forName(ICON_ADAPTER, false, loader);
            final Class<?> pagerAdapterClass = Class.forName(PAGER_ADAPTER, false, loader);

            Method initView = findMethod(fragmentClass, "initView", View.class);
            Method initPackNames = findNoArgMethod(fragmentClass, "initPackNames");
            Method packageName = findNoArgMethod(fragmentClass, "getIconPackageName");
            Method destroyView = findNoArgMethod(fragmentClass, "onDestroyView");
            Method destroy = findNoArgMethod(fragmentClass, "onDestroy");
            Method setData = findMapMethod(iconAdapterClass, "setData");
            Method clearData = findNoArgMethod(iconAdapterClass, "clearData");
            Method onBind = findPagerBindMethod(pagerAdapterClass);
            Method pagerSetData = findPagerSetDataMethod(pagerAdapterClass);

            hook(initView).intercept(chain -> {
                Object result = chain.proceed();
                Object fragment = chain.getThisObject();
                FragmentState state = stateFor(fragment, loader, true);
                state.panelView = asView(readField(fragment, "mPanelView"));
                state.hostView = asView(chain.getArg(0));
                attachSearchEditor(state);
                postAttachSearchEditor(state);
                registerViewPager(state);
                return result;
            });

            if (initPackNames != null) {
                hook(initPackNames).intercept(chain -> {
                    Object result = chain.proceed();
                    FragmentState state = stateFor(chain.getThisObject(), loader, true);
                    state.panelView = asView(readField(chain.getThisObject(), "mPanelView"));
                    postAttachSearchEditor(state);
                    registerViewPager(state);
                    return result;
                });
            }

            if (packageName != null) {
                hook(packageName).intercept(chain -> {
                    Object result = chain.proceed();
                    FragmentState state = stateFor(chain.getThisObject(), loader, false);
                    if (state != null && state.query.length() > 0) {
                        int page = currentPage(chain.getThisObject());
                        Object adapter = boundAdapter(state, page);
                        if (adapter != null) scheduleSearch(state, page, adapter);
                    }
                    return result;
                });
            }

            if (destroyView != null) {
                hook(destroyView).intercept(chain -> {
                    clearState(chain.getThisObject());
                    return chain.proceed();
                });
            }
            if (destroy != null) {
                hook(destroy).intercept(chain -> {
                    clearState(chain.getThisObject());
                    return chain.proceed();
                });
            }

            hook(setData).intercept(chain -> {
                Object adapter = chain.getThisObject();
                Object argument = chain.getArg(0);
                if (argument instanceof Map) captureNativeData(adapter, (Map<?, ?>) argument);
                FragmentState owner = ownerOf(adapter);
                if (owner != null && owner.query.length() > 0) {
                    return null;
                }
                return chain.proceed();
            });

            if (clearData != null) {
                hook(clearData).intercept(chain -> {
                    Object adapter = chain.getThisObject();
                    FragmentState owner = ownerOf(adapter);
                    if (owner != null && owner.query.length() > 0) return null;
                    synchronized (LOCK) {
                        NATIVE_DATA.remove(adapter);
                    }
                    return chain.proceed();
                });
            }

            hook(onBind).intercept(chain -> {
                Object result = chain.proceed();
                Object pager = chain.getThisObject();
                int page = intArg(chain.getArg(1), 0);
                registerBoundAdapter(pager, page);
                return result;
            });

            if (pagerSetData != null) {
                hook(pagerSetData).intercept(chain -> {
                    Object pager = chain.getThisObject();
                    Object argument = chain.getArg(0);
                    int page = intArg(chain.getArg(1), 0);
                    Object child = childAdapter(pager, page);
                    if (child != null && argument instanceof Map) {
                        captureNativeData(child, (Map<?, ?>) argument);
                    }
                    FragmentState state;
                    synchronized (LOCK) {
                        state = ownerOfPagerLocked(pager);
                    }
                    postAttachSearchEditor(state);
                    return chain.proceed();
                });
            }

        } catch (Throwable t) {
            synchronized (LOCK) {
                INSTALLED.remove(loader);
            }
            throw t;
        }
    }

    private static FragmentState stateFor(Object fragment, ClassLoader loader, boolean create) {
        if (fragment == null) return null;
        synchronized (LOCK) {
            FragmentState state = STATES.get(fragment);
            if (state == null && create) {
                state = new FragmentState(fragment, loader);
                STATES.put(fragment, state);
            }
            return state;
        }
    }

    private static void registerViewPager(FragmentState state) {
        if (state == null) return;
        Object fragment = state.fragment.get();
        if (fragment == null) return;
        Object pager = readField(fragment, "mAdapter");
        if (pager != null) {
            synchronized (LOCK) {
                state.viewPagerAdapter = pager;
            }
        }
    }

    private static void attachSearchEditor(final FragmentState state) {
        try {
            if (state == null) return;
            final ViewGroup parent = searchParent(state);
            if (parent == null) return;
            if (parent.findViewWithTag(SEARCH_TAG) != null) return;
            final Context context = parent.getContext();
            if (context == null) return;

        final float density = context.getResources().getDisplayMetrics().density;
        final int barHeight = dp(density, SEARCH_BAR_HEIGHT_DP);
        final int horizontalMargin = dp(density, SEARCH_BAR_HORIZONTAL_MARGIN_DP);
        final int verticalMargin = dp(density, SEARCH_BAR_VERTICAL_MARGIN_DP);

        FrameLayout searchBar = new FrameLayout(context);
        searchBar.setTag(SEARCH_TAG);
        searchBar.setFocusable(true);
        searchBar.setFocusableInTouchMode(true);
        int backgroundId = targetResourceId(context, "drawable", "coui_searchview_edit_background");
        if (backgroundId != 0) {
            try {
                searchBar.setBackgroundResource(backgroundId);
            } catch (Throwable ignored) {
            }
        } else {
            GradientDrawable fallback = new GradientDrawable();
            fallback.setShape(GradientDrawable.RECTANGLE);
            fallback.setCornerRadius(dp(density, 18));
            fallback.setColor(resolveColor(context, "coui_searchview_hint_background",
                    0x14000000));
            searchBar.setBackground(fallback);
        }

        EditText editor = new EditText(context);
        editor.setTag(SEARCH_TAG + "_editor");
        editor.setSingleLine(true);
        editor.setInputType(1);
        editor.setImeOptions(3);
        editor.setTextSize(16.0f);
        editor.setGravity(Gravity.CENTER_VERTICAL);
        editor.setBackground(null);
        editor.setPadding(dp(density, SEARCH_ICON_SIZE_DP + SEARCH_ICON_START_DP + 8),
                0, dp(density, SEARCH_CLEAR_SIZE_DP + 4), 0);
        setColorStateList(editor, true, context, "coui_search_view_input_text_color");
        setColorStateList(editor, false, context, "coui_search_view_hint_color_selector");
        int hintId = targetResourceId(context, "string", "search_entry_text");
        if (hintId != 0) {
            try {
                editor.setHint(context.getResources().getString(hintId));
            } catch (Throwable ignored) {
                editor.setHint("搜索图标资源名");
            }
        } else {
            editor.setHint("搜索图标资源名");
        }
        FrameLayout.LayoutParams editorParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        searchBar.addView(editor, editorParams);

        ImageView searchIcon = new ImageView(context);
        int searchIconId = targetResourceId(context, "drawable", "coui_search_view_icon");
        if (searchIconId != 0) {
            try {
                searchIcon.setImageResource(searchIconId);
            } catch (Throwable ignored) {
            }
        }
        searchIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                dp(density, SEARCH_ICON_SIZE_DP), dp(density, SEARCH_ICON_SIZE_DP),
                Gravity.START | Gravity.CENTER_VERTICAL);
        iconParams.leftMargin = dp(density, SEARCH_ICON_START_DP);
        searchBar.addView(searchIcon, iconParams);

        final ImageButton clearButton = new ImageButton(context);
        int clearIconId = targetResourceId(context, "drawable", "coui_search_clear_selector");
        if (clearIconId == 0) {
            clearIconId = targetResourceId(context, "drawable", "ic_edit_text_delete_search_view");
        }
        if (clearIconId != 0) {
            try {
                clearButton.setImageResource(clearIconId);
            } catch (Throwable ignored) {
            }
        }
        clearButton.setBackground(null);
        clearButton.setPadding(0, 0, 0, 0);
        clearButton.setContentDescription("清除搜索");
        clearButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams clearParams = new FrameLayout.LayoutParams(
                dp(density, SEARCH_CLEAR_SIZE_DP), dp(density, SEARCH_CLEAR_SIZE_DP),
                Gravity.END | Gravity.CENTER_VERTICAL);
        clearParams.rightMargin = dp(density, 4);
        searchBar.addView(clearButton, clearParams);
        clearButton.setOnClickListener(v -> editor.setText("") );

        ViewGroup.MarginLayoutParams barParams = searchLayoutParams(parent, barHeight);
        barParams.leftMargin = horizontalMargin;
        barParams.topMargin = verticalMargin;
        barParams.rightMargin = horizontalMargin;
        barParams.bottomMargin = verticalMargin;
        parent.addView(searchBar, 0, barParams);
        int pagerId = targetResourceId(context, "id", "icon_view_pager");
        if (pagerId != 0) {
            View pager = parent.findViewById(pagerId);
            if (pager != null && pager.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams pagerParams =
                        (LinearLayout.LayoutParams) pager.getLayoutParams();
                pagerParams.height = 0;
                pagerParams.weight = 1.0f;
                pager.setLayoutParams(pagerParams);
            }
        }
            editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearButton.setVisibility(s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
                onQueryChanged(state, s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
            });
        } catch (Throwable ignored) {
        }
    }

    private static ViewGroup searchParent(FragmentState state) {
        View panel = state.panelView;
        if (panel instanceof ViewGroup) return (ViewGroup) panel;
        View host = state.hostView;
        if (host instanceof ViewGroup) return (ViewGroup) host;
        return null;
    }

    private static void postAttachSearchEditor(final FragmentState state) {
        View target = state == null ? null : state.panelView;
        if (target == null) target = state == null ? null : state.hostView;
        if (target == null) return;
        target.post(new Runnable() {
            @Override
            public void run() {
                attachSearchEditor(state);
            }
        });
    }

    private static ViewGroup.MarginLayoutParams searchLayoutParams(ViewGroup parent, int height) {
        if (parent instanceof LinearLayout) {
            return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        }
        if (parent instanceof FrameLayout) {
            return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        }
        return new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private static int dp(float density, int value) {
        return Math.max(1, (int) (value * density + 0.5f));
    }

    private static int resolveColor(Context context, String name, int fallback) {
        try {
            int id = context.getResources().getIdentifier(name, "color", TARGET_PACKAGE);
            if (id != 0) return context.getResources().getColor(id, context.getTheme());
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static void setColorStateList(EditText editor, boolean text, Context context,
                                          String name) {
        try {
            int id = context.getResources().getIdentifier(name, "color", TARGET_PACKAGE);
            if (id == 0) return;
            android.content.res.ColorStateList colors =
                    context.getResources().getColorStateList(id, context.getTheme());
            if (text) editor.setTextColor(colors);
            else editor.setHintTextColor(colors);
        } catch (Throwable ignored) {
        }
    }

    private static int targetResourceId(Context context, String type, String name) {
        String[] packages = new String[]{TARGET_PACKAGE, "com.android.launcher3", context.getPackageName()};
        for (String packageName : packages) {
            if (packageName == null) continue;
            try {
                int id = context.getResources().getIdentifier(name, type, packageName);
                if (id != 0) return id;
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static void onQueryChanged(FragmentState state, String raw) {
        if (state == null) return;
        String query = normalize(raw);
        List<PageEntry> pages;
        synchronized (LOCK) {
            state.query = query;
            ++state.generation;
            state.scheduledGeneration.clear();
            for (Future<?> future : state.futures.values()) {
                if (future != null) future.cancel(true);
            }
            state.futures.clear();
            pages = snapshotPages(state);
        }
        cancelTargetTask(state);

        if (query.length() == 0) {
            for (PageEntry page : pages) restorePage(state, page.page, page.adapter);
        } else {
            for (PageEntry page : pages) scheduleSearch(state, page.page, page.adapter);
        }
    }

    private static void scheduleSearch(final FragmentState state, final int page,
                                       final Object adapter) {
        if (state == null || adapter == null || state.query.length() == 0) return;
        final Object fragment = state.fragment.get();
        final Context context = contextFor(state);
        final String pack = packageForPage(fragment, page);
        if (fragment == null || context == null || pack.length() == 0) return;
        final long generation;
        synchronized (LOCK) {
            if (state.query.length() == 0) return;
            Long previous = state.scheduledGeneration.get(page);
            if (previous != null && previous.longValue() == state.generation) return;
            state.scheduledGeneration.put(page, state.generation);
            Future<?> old = state.futures.get(page);
            if (old != null) old.cancel(true);
            generation = state.generation;
        }

        Future<?> future = SEARCH_EXECUTOR.submit(new Runnable() {
            @Override
            public void run() {
                LinkedHashMap<Object, Object> result;
                try {
                    result = loadMatchingIcons(state.loader, context, pack, state.query);
                } catch (Throwable ignored) {
                    result = new LinkedHashMap<Object, Object>();
                }
                postResult(state, page, adapter, generation, result);
            }
        });
        synchronized (LOCK) {
            state.futures.put(page, future);
        }
    }

    private static void restorePage(final FragmentState state, final int page,
                                    final Object adapter) {
        if (state == null || adapter == null || state.query.length() != 0) return;
        LinkedHashMap<Object, Object> nativeData;
        synchronized (LOCK) {
            LinkedHashMap<Object, Object> cached = NATIVE_DATA.get(adapter);
            nativeData = cached == null
                    ? new LinkedHashMap<Object, Object>()
                    : new LinkedHashMap<Object, Object>(cached);
        }
        replaceAdapterData(adapter, nativeData);
        if (!nativeData.isEmpty()) return;

        final Object fragment = state.fragment.get();
        final Context context = contextFor(state);
        final String pack = packageForPage(fragment, page);
        if (fragment == null || context == null || pack.length() == 0) return;
        final long generation = state.generation;
        SEARCH_EXECUTOR.submit(new Runnable() {
            @Override
            public void run() {
                LinkedHashMap<Object, Object> result;
                try {
                    result = loadFirstPage(state.loader, context, pack);
                } catch (Throwable ignored) {
                    result = new LinkedHashMap<Object, Object>();
                }
                View panel = state.panelView;
                if (panel != null) {
                    final LinkedHashMap<Object, Object> ready = result;
                    panel.post(new Runnable() {
                        @Override
                        public void run() {
                            if (state.query.length() == 0 && state.generation == generation
                                    && boundAdapter(state, page) == adapter) {
                                replaceAdapterData(adapter, ready);
                            }
                        }
                    });
                }
            }
        });
    }

    private static void postResult(final FragmentState state, final int page,
                                   final Object adapter, final long generation,
                                   final LinkedHashMap<Object, Object> result) {
        final View panel = state.panelView;
        if (panel == null) return;
        panel.post(new Runnable() {
            @Override
            public void run() {
                if (state.query.length() == 0 || state.generation != generation
                        || boundAdapter(state, page) != adapter) return;
                replaceAdapterData(adapter, result);
                synchronized (LOCK) {
                    state.futures.remove(page);
                }
            }
        });
    }

    private static LinkedHashMap<Object, Object> loadMatchingIcons(ClassLoader loader,
                                                                     Context context,
                                                                     String pack,
                                                                     String query)
            throws Throwable {
        ArrayList<Object> all = fullPackItems(loader, context, pack);
        LinkedHashMap<Object, Object> result = new LinkedHashMap<Object, Object>();
        if (all == null || all.isEmpty()) return result;
        String needle = normalize(query);
        ArrayList<Object> matched = new ArrayList<Object>();
        for (Object descriptor : all) {
            String name = resourceName(descriptor);
            if (name != null && name.toLowerCase(Locale.ROOT).contains(needle)) {
                matched.add(descriptor);
            }
        }
        return loadResourceDrawables(context, pack, matched);
    }

    private static LinkedHashMap<Object, Object> loadFirstPage(ClassLoader loader,
                                                                 Context context,
                                                                 String pack)
            throws Throwable {
        ArrayList<Object> all = fullPackItems(loader, context, pack);
        if (all == null) return new LinkedHashMap<Object, Object>();
        return loadResourceDrawables(context, pack, all.subList(0,
                Math.min(ICONS_PER_PAGE, all.size())));
    }

    private static LinkedHashMap<Object, Object> loadResourceDrawables(Context context,
                                                                         String pack,
                                                                         List<Object> descriptors)
            throws Throwable {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<Object, Object>();
        if (descriptors == null || descriptors.isEmpty()) return result;
        Resources resources = context.getPackageManager().getResourcesForApplication(pack);
        for (Object descriptor : descriptors) {
            if (Thread.currentThread().isInterrupted()) break;
            String name = resourceName(descriptor);
            if (name == null || name.length() == 0) continue;
            int id = resources.getIdentifier(name, "drawable", pack);
            if (id <= 0) continue;
            try {
                if (!"drawable".equals(resources.getResourceTypeName(id))) continue;
                Drawable drawable = resources.getDrawable(id, null);
                if (drawable != null) result.put(descriptor, drawable);
            } catch (Resources.NotFoundException ignored) {
            }
        }
        return result;
    }

    private static ArrayList<Object> fullPackItems(ClassLoader loader, Context context,
                                                    String pack) throws Throwable {
        synchronized (LOCK) {
            PackData cached = PACKS.get(pack);
            if (cached != null && cached.loader == loader && cached.items != null) {
                return new ArrayList<Object>(cached.items);
            }
        }
        Method items = findIconPackItemsMethod(loader);
        Object value = items.invoke(null, context, pack);
        ArrayList<Object> result = new ArrayList<Object>();
        if (value instanceof List) result.addAll((List<?>) value);

        // The ADW/Nova icon-pack contract exposes the complete manual picker
        // through drawable.xml. Merge it with the launcher's parsed list so
        // search never depends on the pages that have already been rendered.
        LinkedHashMap<String, Object> byName = new LinkedHashMap<String, Object>();
        for (Object descriptor : result) {
            String name = resourceName(descriptor);
            if (name != null && name.length() > 0 && !byName.containsKey(name)) {
                byName.put(name, descriptor);
            }
        }
        ArrayList<String> declaredNames = readDeclaredResourceNames(context, pack);
        for (String name : declaredNames) {
            if (!byName.containsKey(name)) {
                Object descriptor = newDescriptor(loader, result, name);
                if (descriptor != null) {
                    result.add(descriptor);
                    byName.put(name, descriptor);
                }
            }
        }
        if (result.isEmpty()) return result;
        cachePackItems(loader, pack, result);
        return result;
    }

    private static void cachePackItems(ClassLoader loader, String pack, List<?> items) {
        if (pack == null || items == null) return;
        PackData data = new PackData(loader, new ArrayList<Object>(items));
        synchronized (LOCK) {
            PACKS.put(pack, data);
        }
    }

    private static Object newDescriptor(ClassLoader loader, List<Object> existing,
                                         String resourceName) {
        Class<?> descriptorClass = null;
        for (Object item : existing) {
            if (item != null) {
                descriptorClass = item.getClass();
                break;
            }
        }
        String[] candidates = descriptorClass == null
                ? new String[]{DESCRIPTOR_PREFIX + "b", DESCRIPTOR_PREFIX + "a"}
                : new String[]{descriptorClass.getName()};
        for (String className : candidates) {
            try {
                Class<?> type = Class.forName(className, false, loader);
                java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object descriptor = constructor.newInstance();
                Field field = findField(type, DESCRIPTOR_RESOURCE_FIELD);
                field.set(descriptor, resourceName);
                return descriptor;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static ArrayList<String> readDeclaredResourceNames(Context context, String pack) {
        ArrayList<String> result = new ArrayList<String>();
        if (context == null || pack == null || pack.length() == 0) return result;
        try {
            Resources resources = context.getPackageManager().getResourcesForApplication(pack);
            int drawableXml = resources.getIdentifier("drawable", "xml", pack);
            if (drawableXml != 0) {
                XmlResourceReader reader = new XmlResourceReader(resources.getXml(drawableXml));
                readDrawableNames(reader.parser, result);
                reader.close();
            }
            if (result.isEmpty()) {
                readAssetDrawableNames(resources, "drawable.xml", result);
            }
            if (result.isEmpty()) {
                int appFilterXml = resources.getIdentifier("appfilter", "xml", pack);
                if (appFilterXml != 0) {
                    XmlResourceReader reader = new XmlResourceReader(resources.getXml(appFilterXml));
                    readDrawableNames(reader.parser, result);
                    reader.close();
                }
                if (result.isEmpty()) {
                    readAssetDrawableNames(resources, "appfilter.xml", result);
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static void readAssetDrawableNames(Resources resources, String assetName,
                                               ArrayList<String> output) {
        InputStream input = null;
        try {
            input = resources.getAssets().open(assetName);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            readDrawableNames(parser, output);
        } catch (Throwable ignored) {
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void readDrawableNames(XmlPullParser parser, ArrayList<String> output)
            throws Throwable {
        if (parser == null) return;
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() != XmlPullParser.START_TAG
                    || !"item".equalsIgnoreCase(parser.getName())) continue;
            String name = parser.getAttributeValue(null, "drawable");
            if (name == null) continue;
            name = name.trim();
            if (name.startsWith("@drawable/")) name = name.substring("@drawable/".length());
            if (name.length() == 0 || name.startsWith(":")) continue;
            if (!output.contains(name)) output.add(name);
            if (output.size() >= MAX_ICON_PACK_ITEMS) return;
        }
    }

    private static final class XmlResourceReader {
        final XmlPullParser parser;

        XmlResourceReader(XmlPullParser parser) {
            this.parser = parser;
        }

        void close() {
            if (parser instanceof android.content.res.XmlResourceParser) {
                ((android.content.res.XmlResourceParser) parser).close();
            }
        }
    }

    private static void registerBoundAdapter(Object pager, int page) {
        if (pager == null) return;
        FragmentState state;
        synchronized (LOCK) {
            state = ownerOfPagerLocked(pager);
        }
        if (state == null) return;
        Object child = childAdapter(pager, page);
        if (child == null) return;
        synchronized (LOCK) {
            Object previous = state.pageAdapters.put(page, child);
            if (previous != null && previous != child) {
                ADAPTER_OWNERS.remove(previous);
            }
            for (Map.Entry<Integer, Object> entry
                    : new ArrayList<Map.Entry<Integer, Object>>(state.pageAdapters.entrySet())) {
                if (entry.getKey().intValue() != page && entry.getValue() == child) {
                    state.pageAdapters.remove(entry.getKey());
                }
            }
            ADAPTER_OWNERS.put(child, state);
        }
        attachSearchEditor(state);
        if (state.query.length() > 0) scheduleSearch(state, page, child);
    }

    private static Object childAdapter(Object pager, int page) {
        Object value = readField(pager, "mRVAdapterList");
        if (!(value instanceof Map)) return null;
        return ((Map<?, ?>) value).get(Integer.valueOf(page));
    }

    private static FragmentState ownerOf(Object adapter) {
        synchronized (LOCK) {
            return ADAPTER_OWNERS.get(adapter);
        }
    }

    private static FragmentState ownerOfPagerLocked(Object pager) {
        for (FragmentState state : STATES.values()) {
            if (state != null && state.viewPagerAdapter == pager) return state;
        }
        return null;
    }

    private static Object boundAdapter(FragmentState state, int page) {
        synchronized (LOCK) {
            return state.pageAdapters.get(page);
        }
    }

    private static List<PageEntry> snapshotPages(FragmentState state) {
        ArrayList<PageEntry> result = new ArrayList<PageEntry>();
        for (Map.Entry<Integer, Object> entry : state.pageAdapters.entrySet()) {
            result.add(new PageEntry(entry.getKey().intValue(), entry.getValue()));
        }
        return result;
    }

    private static void captureNativeData(Object adapter, Map<?, ?> incoming) {
        if (adapter == null || incoming == null || incoming.isEmpty()) return;
        synchronized (LOCK) {
            LinkedHashMap<Object, Object> cached = NATIVE_DATA.get(adapter);
            if (cached == null) {
                cached = new LinkedHashMap<Object, Object>();
                NATIVE_DATA.put(adapter, cached);
            }
            if (readInt(adapter, "mRefreshStartPosition", 0) == 0 && cached.isEmpty()) {
                cached.clear();
            }
            cached.putAll(incoming);
        }
    }

    private static void cancelTargetTask(FragmentState state) {
        Object fragment = state.fragment.get();
        if (fragment == null) return;
        Object task = readField(fragment, "mFetchIconListTask");
        if (task != null) {
            try {
                Method cancel = findMethod(task.getClass(), "cancel", Boolean.TYPE);
                cancel.invoke(task, Boolean.TRUE);
            } catch (Throwable ignored) {
            }
            writeField(fragment, "mFetchIconListTask", null);
        }
    }

    private static void clearState(Object fragment) {
        FragmentState state;
        synchronized (LOCK) {
            state = STATES.remove(fragment);
            if (state == null) return;
            for (Future<?> future : state.futures.values()) {
                if (future != null) future.cancel(true);
            }
            state.futures.clear();
            for (Object adapter : state.pageAdapters.values()) {
                ADAPTER_OWNERS.remove(adapter);
                NATIVE_DATA.remove(adapter);
            }
            state.pageAdapters.clear();
            state.query = "";
            state.panelView = null;
            state.hostView = null;
        }
        cancelTargetTask(state);
    }

    private static String packageForPage(Object fragment, int page) {
        if (fragment == null) return "";
        Object list = readField(fragment, "mAppInfoList");
        if (list instanceof List) {
            try {
                Object info = ((List<?>) list).get(page < 0 ? 0 : page);
                Object name = readField(info, "packageName");
                if (name instanceof String) return (String) name;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static Context contextFor(FragmentState state) {
        View panel = state.panelView;
        return panel == null ? null : panel.getContext();
    }

    private static int currentPage(Object fragment) {
        int page = readInt(fragment, "mCurrentPage", 0);
        return page == MIN_PAGE ? 0 : page;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String resourceName(Object descriptor) {
        if (descriptor == null || !descriptor.getClass().getName().startsWith(DESCRIPTOR_PREFIX)) {
            return null;
        }
        Class<?> type = descriptor.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(DESCRIPTOR_RESOURCE_FIELD);
                field.setAccessible(true);
                Object value = field.get(descriptor);
                return value instanceof String ? (String) value : null;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void replaceAdapterData(Object adapter, Map<?, ?> values) {
        if (adapter == null || values == null) return;
        try {
            Map data = (Map) findField(adapter.getClass(), "mData").get(adapter);
            List items = (List) findField(adapter.getClass(), "mItems").get(adapter);
            List drawables = (List) findField(adapter.getClass(), "mDrawables").get(adapter);
            Field startField = findField(adapter.getClass(), "mRefreshStartPosition");
            Field insertedField = findField(adapter.getClass(), "mInsertedOne");
            data.clear();
            data.putAll(values);
            items.clear();
            items.addAll(values.keySet());
            drawables.clear();
            drawables.addAll(values.values());
            startField.setInt(adapter, values.isEmpty() ? 0 : values.size() - 1);
            Object atomic = insertedField.get(adapter);
            if (atomic instanceof AtomicBoolean) ((AtomicBoolean) atomic).set(false);
            Method notify = findNoArgMethod(adapter.getClass(), "notifyDataSetChanged");
            if (notify != null) notify.invoke(adapter);
        } catch (Throwable ignored) {
        }
    }

    private static Method findIconPackItemsMethod(ClassLoader loader) throws ClassNotFoundException {
        Class<?> helper = Class.forName(HELPER, false, loader);
        for (Method method : helper.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getName().equals("getIconPackItems") && Modifier.isStatic(method.getModifiers())
                    && params.length == 2 && Context.class.isAssignableFrom(params[0])
                    && params[1] == String.class) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodError("getIconPackItems");
    }

    private static Method findMethod(Class<?> type, String name, Class<?> parameter)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName()) && method.getParameterTypes().length == 1
                        && parameter.isAssignableFrom(method.getParameterTypes()[0])) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    private static Method findMapMethod(Class<?> type, String name) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName()) && method.getParameterTypes().length == 1
                        && Map.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    private static Method findPagerBindMethod(Class<?> type) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getName().equals("onBindViewHolder") && params.length == 2
                        && params[1] == Integer.TYPE) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException("onBindViewHolder");
    }

    private static Method findPagerSetDataMethod(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getName().equals("setData") && params.length == 2
                        && Map.class.isAssignableFrom(params[0]) && params[1] == Integer.TYPE) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName()) && method.getParameterTypes().length == 0) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object readField(Object object, String name) {
        if (object == null) return null;
        try {
            return findField(object.getClass(), name).get(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeField(Object object, String name, Object value) {
        if (object == null) return;
        try {
            findField(object.getClass(), name).set(object, value);
        } catch (Throwable ignored) {
        }
    }

    private static int readInt(Object object, String name, int fallback) {
        if (object == null) return fallback;
        try {
            return findField(object.getClass(), name).getInt(object);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int intArg(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static View asView(Object value) {
        return value instanceof View ? (View) value : null;
    }

    private static final class FragmentState {
        final WeakReference<Object> fragment;
        final ClassLoader loader;
        final Map<Integer, Object> pageAdapters = new HashMap<Integer, Object>();
        final Map<Integer, Long> scheduledGeneration = new HashMap<Integer, Long>();
        final Map<Integer, Future<?>> futures = new HashMap<Integer, Future<?>>();
        volatile View panelView;
        volatile View hostView;
        volatile Object viewPagerAdapter;
        volatile String query = "";
        volatile long generation;

        FragmentState(Object fragment, ClassLoader loader) {
            this.fragment = new WeakReference<Object>(fragment);
            this.loader = loader;
        }
    }

    private static final class PageEntry {
        final int page;
        final Object adapter;

        PageEntry(int page, Object adapter) {
            this.page = page;
            this.adapter = adapter;
        }
    }

    private static final class PackData {
        final ClassLoader loader;
        final ArrayList<Object> items;

        PackData(ClassLoader loader, ArrayList<Object> items) {
            this.loader = loader;
            this.items = items;
        }
    }
}
