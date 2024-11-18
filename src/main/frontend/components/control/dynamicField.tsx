import React from 'react';
import {Checkbox, DatePicker, NumberField, TextField} from "@vaadin/react-components";
import {isString} from "@vaadin/router/dist/resolver/utils";

function convertValue(newValue: string | number | Date): any {
    if (isString(newValue)) {
        if (newValue.match(/^\d{4}-\d{2}-\d{2}$/)) {
            return new Date(newValue);
        }
    }
    return newValue;
}

export function getDynamicField(value: any, javaType:String): Function {
    // @ts-ignore
    return function ({field}) {
        switch (javaType) {
            case 'java.lang.String':
                value = convertValue(value);
                if(value instanceof Date) {
                    return <DatePicker {...field} value={value} />;
                }
                return <TextField {...field} />;
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
    }
}