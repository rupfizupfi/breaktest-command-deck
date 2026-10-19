import {
    ComboBoxDataProviderCallback,
    ComboBoxDataProviderParams,
    MultiSelectComboBox,
    MultiSelectComboBoxElement,
    MultiSelectComboBoxProps
} from "@vaadin/react-components";
import React from "react";
import {AutoComboService} from "cms/components/combobox/service";

type AutoComboBoxProps<T> = MultiSelectComboBoxProps<T> & {
    service: AutoComboService<T>;
}

/** MultiSelectComboBox whose items are filtered server-side by `service`. */
const AutMultiSelectComboBox = React.forwardRef<MultiSelectComboBoxElement<any>, AutoComboBoxProps<any>>((props, ref) => {
    const service = props.service;
    // A failed fetch still answers the callback, empty: the rpc middleware toasts the cause, and an
    // unanswered callback would leave the dropdown spinning.
    const dataProvider = (input: ComboBoxDataProviderParams, callback: ComboBoxDataProviderCallback<any>) => service(input.filter)
        .then((value) => callback(value, value.length), () => callback([], 0));

    return <MultiSelectComboBox {...props} ref={ref} dataProvider={ dataProvider }/>;
});

export default AutMultiSelectComboBox;