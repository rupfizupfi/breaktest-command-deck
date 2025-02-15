import type { AbstractModel, DetachedModelConstructor, Value } from '@vaadin/hilla-lit-form';
import { Button } from '@vaadin/react-components/Button.js';
import { SplitLayout } from '@vaadin/react-components/SplitLayout.js';
import { type JSX, useId, useRef, useState } from 'react';
import {AutoForm, type AutoFormProps, AutoGrid, type AutoGridProps, AutoGridRef, CrudService, emptyItem} from "@vaadin/hilla-react-crud";
import {AutoCrudDialog} from "Frontend/components/autocrud/AutoCrudDialog";
import {useMediaQuery} from "Frontend/components/autocrud/mediaQuery";
import {ComponentStyleProps} from "Frontend/components/autocrud/util";
import 'Frontend/components/autocrud/autoCrud.css';
// @ts-ignore
import {AutoFormFieldProps} from "@vaadin/hilla-react-crud/autoform-field";


export type AutoCrudFormHeaderRenderer<TItem> = (
    editedItem: TItem | null,
    disabled: boolean,
) => JSX.Element | null | undefined;

export type AutoCrudFormProps<TModel extends AbstractModel> = Omit<
    Partial<AutoFormProps<TModel>>,
    'disabled' | 'item' | 'model' | 'onDeleteSuccess' | 'onSubmitSuccess' | 'service'
> &
    Readonly<{
        /**
         * A custom renderer function to create the header for the form. The
         * function receives the edited item as the first parameter, and a boolean
         * indicating whether the form is disabled as the second parameter. The
         * edited item is `null` when creating a new item.
         *
         * By default, the header shows "New item" when creating a new item, and
         * "Edit item" when editing an existing item.
         */
        headerRenderer?: AutoCrudFormHeaderRenderer<Value<TModel>>;
        disableAddButtons?: boolean;
    }>;

export type AutoCrudGridProps<TItem> = Omit<
    Partial<AutoGridProps<TItem>>,
    'model' | 'onActiveItemChanged' | 'selectedItems' | 'service'
>;

export type AutoCrudProps<TModel extends AbstractModel = AbstractModel> = ComponentStyleProps &
    Readonly<{
        /**
         * The service to use for fetching the data, as well saving and deleting
         * items. This must be a TypeScript service that has been generated by Hilla
         * from a backend Java service that implements the
         * `com.vaadin.hilla.crud.CrudService` interface.
         */
        service: CrudService<Value<TModel>>;
        /**
         * The entity model to use for the CRUD. This determines which columns to
         * show in the grid, and which fields to show in the form. This must be a
         * Typescript model class that has been generated by Hilla from a backend
         * Java class. The model must match with the type of the items returned by
         * the service. For example, a `PersonModel` can be used with a service that
         * returns `Person` instances.
         *
         * By default, the grid shows columns for all properties of the model which
         * have a type that is supported. Use the `gridProps.visibleColumns` option
         * to customize which columns to show and in which order.
         *
         * By default, the form shows fields for all properties of the model which
         * have a type that is supported. Use the `formProps.visibleFields`
         * option to customize which fields to show and in which order.
         */
        model: DetachedModelConstructor<TModel>;
        /**
         * The property to use to detect an item's ID. The item ID is required for
         * deleting items via the `CrudService.delete` method as well as keeping the
         * selection state after reloading the grid.
         *
         * By default, the component uses the property annotated with
         * `jakarta.persistence.Id`, or a property named `id`, in that order.
         * This option can be used to override the default behavior, or define the ID
         * property in case a class doesn't have a property matching the defaults.
         */
        itemIdProperty?: string;
        /**
         * Props to pass to the form. See the `AutoForm` component for details.
         */
        formProps?: AutoCrudFormProps<TModel>;
        /**
         * Props to pass to the grid. See the `AutoGrid` component for details.
         */
        gridProps?: AutoCrudGridProps<Value<TModel>>;
        copyItemCallback?: (item: Value<TModel>) => Value<TModel>;
        formFieldUpdater?: (item: Value<TModel>, formProps: AutoFormFieldProps) => void;
    }>;

function defaultFormHeaderRenderer<TItem>(editedItem: TItem | null, disabled: boolean): JSX.Element | null | undefined {
    const style = { color: disabled ? 'var(--lumo-disabled-text-color)' : 'var(--lumo-text-color)' };
    return editedItem ? <h3 style={style}>Edit item</h3> : <h3 style={style}>New item</h3>;
}

function defaultCopyItem<TItem>(item: TItem): TItem {
    const clone = JSON.parse(JSON.stringify(item));
    clone.id = undefined;
    clone.__copy = true;
    return clone;
}

/**
 * Auto CRUD is a component that provides CRUD (create, read, update, delete)
 * functionality based on a Java backend service. It automatically generates a
 * grid that shows data from the service, and a form for creating, updating and
 * deleting items.
 *
 * Example usage:
 * ```tsx
 * import { AutoCrud } from '@hilla/react-crud';
 * import PersonService from 'Frontend/generated/endpoints';
 * import PersonModel from 'Frontend/generated/com/example/application/Person';
 *
 * <AutoCrud service={PersonService} model={PersonModel} />
 * ```
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
                                                           formFieldUpdater
                                                       }: AutoCrudProps<TModel>): JSX.Element {
    const [item, setItem] = useState<Value<TModel> | typeof emptyItem | undefined>(undefined);
    const fullScreen = useMediaQuery('(max-width: 600px), (max-height: 600px)');
    const autoGridRef = useRef<AutoGridRef>(null);
    const {headerRenderer: customFormHeaderRenderer, disableAddButtons, ...autoFormProps} = formProps ?? {};
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

    formFieldUpdater && item && item !== emptyItem && formFieldUpdater(item, autoFormProps);

    const autoForm = (
        <AutoForm
            id={autoFormProps.id ?? `auto-form-${id ?? autoCrudId}`}
            deleteButtonVisible={true}
            {...autoFormProps}
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
        </div>
    );
}
