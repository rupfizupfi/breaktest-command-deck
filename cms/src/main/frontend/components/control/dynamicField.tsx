import {Checkbox, DatePicker, NumberField, TextField} from "@vaadin/react-components";
import type {AutoFormProps} from "@vaadin/hilla-react-crud";

type FieldRenderer = NonNullable<NonNullable<AutoFormProps['fieldOptions']>[string]['renderer']>;

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export function getDynamicField(value: unknown, javaType?: string): FieldRenderer {
    // The editor shape follows the value the form was opened with, not the value being typed.
    const dateShaped = typeof value === 'string' && ISO_DATE.test(value);

    return ({field}) => {
        switch (javaType) {
            case 'java.lang.String':
                // A date-shaped String setting stores the same YYYY-MM-DD text DatePicker binds.
                return dateShaped ? <DatePicker {...field} /> : <TextField {...field} />;
            case 'java.lang.Number':
            case 'java.lang.Integer':
            case 'java.lang.Long':
                return <NumberField {...field} />;
            case 'java.util.Date':
                return <DatePicker {...field} />;
            case 'java.lang.Boolean':
                return <Checkbox {...field} />;
            default:
                return <TextField {...field} />;
        }
    };
}
