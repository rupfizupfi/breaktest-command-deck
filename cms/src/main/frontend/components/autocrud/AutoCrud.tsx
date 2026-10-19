import type { AbstractModel, DetachedModelConstructor, Value } from '@vaadin/hilla-lit-form';
import { Button } from '@vaadin/react-components/Button.js';
import { FormLayout } from '@vaadin/react-components/FormLayout.js';
import { SplitLayout } from '@vaadin/react-components/SplitLayout.js';
import { type ComponentType, createContext, type JSX, useContext, useEffect, useEffectEvent, useId, useMemo, useRef, useState } from 'react';
import {
    AutoForm,
    type AutoFormLayoutRendererProps,
    type AutoFormProps,
    AutoGrid,
    type AutoGridProps,
    AutoGridRef,
    CrudService,
    emptyItem
} from "@vaadin/hilla-react-crud";
import {AutoCrudDialog} from "cms/components/autocrud/AutoCrudDialog";
import {useMediaQuery} from "cms/components/autocrud/mediaQuery";
import {ComponentStyleProps} from "cms/components/autocrud/util";
import 'cms/components/autocrud/autoCrud.css';


/** `__copy` marks a row the Copy button cloned; no generated model carries it. */
export type WithCopyFlag<T> = T & { __copy?: boolean };

export type AutoCrudFormHeaderRenderer<TItem> = (
    editedItem: WithCopyFlag<TItem> | null,
    disabled: boolean,
) => JSX.Element | null | undefined;

export type AutoCrudFormProps<TModel extends AbstractModel> = Omit<
    Partial<AutoFormProps<TModel>>,
    'disabled' | 'item' | 'model' | 'onDeleteSuccess' | 'onSubmitSuccess' | 'service'
> &
    Readonly<{
        headerRenderer?: AutoCrudFormHeaderRenderer<Value<TModel>>;
        disableAddButtons?: boolean;
    }>;

/** The props AutoCrud hands through to its AutoForm. */
export type AutoCrudPassThroughFormProps<TModel extends AbstractModel> =
    Omit<AutoCrudFormProps<TModel>, 'headerRenderer' | 'disableAddButtons'>;

/** What the form currently holds. `item` is null for "nothing" and for a new, empty item. */
export type AutoCrudEditState<TItem> = Readonly<{
    item: WithCopyFlag<TItem> | null;
    /** The form holds edits not yet submitted, so `item` is not what the form shows. */
    dirty: boolean;
}>;

export type AutoCrudGridProps<TItem> = Omit<
    Partial<AutoGridProps<TItem>>,
    'model' | 'onActiveItemChanged' | 'selectedItems' | 'service'
>;

export type AutoCrudProps<TModel extends AbstractModel = AbstractModel> = ComponentStyleProps &
    Readonly<{
        service: CrudService<Value<TModel>>;
        model: DetachedModelConstructor<TModel>;
        itemIdProperty?: string;
        formProps?: AutoCrudFormProps<TModel>;
        gridProps?: AutoCrudGridProps<Value<TModel>>;
        copyItemCallback?: (item: Value<TModel>) => Value<TModel>;
        /** Derives the form props for the selected item. Must return new objects, never mutate. */
        formPropsForItem?: (item: Value<TModel>, formProps: AutoCrudPassThroughFormProps<TModel>) => AutoCrudPassThroughFormProps<TModel>;
        onEditStateChange?: (state: AutoCrudEditState<Value<TModel>>) => void;
    }>;

function defaultFormHeaderRenderer<TItem>(editedItem: TItem | null, disabled: boolean): JSX.Element | null | undefined {
    const style = { color: disabled ? 'var(--lumo-disabled-text-color)' : 'var(--lumo-text-color)' };
    return editedItem ? <h3 style={style}>Edit item</h3> : <h3 style={style}>New item</h3>;
}

function defaultCopyItem<TItem>(item: TItem): WithCopyFlag<TItem> {
    const clone = JSON.parse(JSON.stringify(item)) as WithCopyFlag<TItem> & { id?: unknown; version?: number };
    clone.id = undefined;
    // 0 is the unsaved value of a primitive @Version int; must stay a number on the wire.
    clone.version = 0;
    clone.__copy = true;
    return clone;
}

type DirtyReporting = Readonly<{
    onDirtyChange: (dirty: boolean) => void;
    inner?: ComponentType<AutoFormLayoutRendererProps<any>>;
    formLayoutProps?: AutoFormProps['formLayoutProps'];
}>;

const DirtyReportingContext = createContext<DirtyReporting | null>(null);

/**
 * AutoForm keeps its form private and exposes it only to a layout renderer. Module-level so its
 * identity is stable: a renderer created per render would remount every field on each keystroke.
 */
function DirtyReportingLayout({form, children}: AutoFormLayoutRendererProps<any>): JSX.Element {
    const reporting = useContext(DirtyReportingContext);
    const dirty = form.dirty;
    useEffect(() => {
        reporting?.onDirtyChange(dirty);
    }, [dirty, reporting]);

    const Inner = reporting?.inner;
    return Inner
        ? <Inner form={form}>{children}</Inner>
        : <FormLayout {...reporting?.formLayoutProps}>{children}</FormLayout>;
}

/**
 * Modelled on `AutoCrud` from `@vaadin/hilla-react-crud`, which documents the shared props.
 * Adds row copy (`copyItemCallback`), `formPropsForItem`, `onEditStateChange` and `disableAddButtons`.
 */
export function AutoCrud<TModel extends AbstractModel>({
                                                           service,
                                                           model,
                                                           itemIdProperty,
                                                           formProps,
                                                           gridProps,
                                                           style,
                                                           id,
                                                           className,
                                                           copyItemCallback,
                                                           formPropsForItem,
                                                           onEditStateChange
                                                       }: AutoCrudProps<TModel>): JSX.Element {
    const [item, setItem] = useState<Value<TModel> | typeof emptyItem | undefined>(undefined);
    const fullScreen = useMediaQuery('(max-width: 600px), (max-height: 600px)');
    const autoGridRef = useRef<AutoGridRef>(null);
    const [dirty, setDirty] = useState(false);
    const {headerRenderer: customFormHeaderRenderer, disableAddButtons, ...passThroughFormProps} = formProps ?? {};
    const autoFormProps: AutoCrudPassThroughFormProps<TModel> = formPropsForItem && item && item !== emptyItem
        ? formPropsForItem(item, passThroughFormProps)
        : passThroughFormProps;

    // An effect event, so a caller passing a fresh callback each render does not re-fire the effect,
    // yet the effect always calls the latest one.
    const reportEditState = useEffectEvent((state: AutoCrudEditState<Value<TModel>>) => onEditStateChange?.(state));
    const editedItem = item && item !== emptyItem ? item as WithCopyFlag<Value<TModel>> : null;
    useEffect(() => {
        reportEditState({item: editedItem, dirty});
    }, [editedItem, dirty]);

    const reportsDirty = onEditStateChange !== undefined;
    const {layoutRenderer, formLayoutProps} = autoFormProps;
    const dirtyReporting = useMemo<DirtyReporting>(
        () => ({onDirtyChange: setDirty, inner: layoutRenderer as DirtyReporting['inner'], formLayoutProps}),
        [layoutRenderer, formLayoutProps]);

    const formHeaderRenderer: AutoCrudFormHeaderRenderer<Value<TModel>> =
        customFormHeaderRenderer ?? defaultFormHeaderRenderer;

    const copyItem = copyItemCallback ?? defaultCopyItem;

    const autoCrudId = useId();

    function refreshGrid() {
        autoGridRef.current?.refresh();
    }

    function handleCancel() {
        setItem(undefined);
    }

    const formHeader = item && item !== emptyItem ? formHeaderRenderer(item, !item) : formHeaderRenderer(null, !item);

    const buttons = disableAddButtons ? null : (
        <div className="auto-crud-toolbar">
            <Button theme="primary" onClick={() => setItem((item && item !== emptyItem) ? copyItem(item) : emptyItem)}>
                + Copy
            </Button>
            <Button theme="primary" onClick={() => setItem(emptyItem)}>
                + New
            </Button>
        </div>
    );

    const mainSection = (
        <div className="auto-crud-main">
            <AutoGrid
                {...gridProps}
                service={service}
                model={model as DetachedModelConstructor<AbstractModel<Value<TModel>>>}
                itemIdProperty={itemIdProperty}
                selectedItems={item && item !== emptyItem ? [item] : []}
                onActiveItemChanged={(e) => {
                    const activeItem = e.detail.value;
                    setItem(activeItem ?? undefined);
                }}
                ref={autoGridRef}
                aria-controls={autoFormProps.id ?? `auto-form-${id ?? autoCrudId}`}
            ></AutoGrid>
            {buttons}
        </div>
    );

    const autoForm = (
        <AutoForm
            id={autoFormProps.id ?? `auto-form-${id ?? autoCrudId}`}
            deleteButtonVisible={true}
            {...autoFormProps}
            layoutRenderer={reportsDirty ? DirtyReportingLayout : layoutRenderer}
            disabled={!item}
            service={service}
            model={model}
            itemIdProperty={itemIdProperty}
            item={item}
            onSubmitSuccess={({item: submittedItem}) => {
                if (fullScreen) {
                    setItem(undefined);
                } else {
                    setItem(submittedItem);
                }
                refreshGrid();
            }}
            onDeleteSuccess={() => {
                setItem(undefined);
                refreshGrid();
            }}
        />
    );

    return (
        <div className={`auto-crud ${className ?? ''}`} id={id} style={style}>
            <DirtyReportingContext.Provider value={dirtyReporting}>
            {fullScreen ? (
                <>
                    {mainSection}
                    <AutoCrudDialog opened={!!item} header={formHeader} onClose={handleCancel}>
                        {autoForm}
                    </AutoCrudDialog>
                </>
            ) : (
                <SplitLayout theme="small">
                    {mainSection}
                    <div className="auto-crud-form">
                        <div className="auto-crud-form-header">{formHeader}</div>
                        {autoForm}
                    </div>
                </SplitLayout>
            )}
            </DirtyReportingContext.Provider>
        </div>
    );
}
