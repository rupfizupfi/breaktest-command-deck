import {MultiSelectComboBox, MultiSelectComboBoxElement, MultiSelectComboBoxProps} from "@vaadin/react-components";
import React from "react";
import {useSignal} from "@vaadin/hilla-react-signals";
import {ComboBoxDataProviderCallback, ComboBoxDataProviderParams} from "@vaadin/combo-box/src/vaadin-combo-box-data-provider-mixin";
import {AutoComboService} from "Frontend/components/combobox/service";

type AutoComboBoxProps<T> = MultiSelectComboBoxProps<T> & {
    service: AutoComboService<T>;
}

/**
 * A ComboBox that automatically filters the items based on the input value against a api.
 */
const AutMultiSelectComboBox = React.forwardRef<MultiSelectComboBoxElement<any>, AutoComboBoxProps<any>>((props, ref) => {
    const service = props.service;
    const dataProvider = (input: ComboBoxDataProviderParams, callback: ComboBoxDataProviderCallback<any>) => service(input.filter).then((value) => callback(value, value.length));

    /**
     * Lazy Loading with Function Data Provider
     * dataProvider={dataProvider}
     */
    return <MultiSelectComboBox {...props} ref={ref} dataProvider={ dataProvider }/>;
});

export default AutMultiSelectComboBox;