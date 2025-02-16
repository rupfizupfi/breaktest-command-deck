import {ComboBox, ComboBoxElement, ComboBoxProps} from "@vaadin/react-components";
import React, {useEffect} from "react";
import {useSignal} from "@vaadin/hilla-react-signals";
import {AutoComboService} from "Frontend/components/combobox/service";

type AutoComboBoxProps<T> = ComboBoxProps<T> & {
    service: AutoComboService<T>;
}

/**
 * A ComboBox that automatically filters the items based on the input value against a api.
 */
const AutoComboBox = React.forwardRef<ComboBoxElement<any>, AutoComboBoxProps<any>>((props, ref) => {
    const input = useSignal<String>("");
    const service = props.service;
    const items = useSignal<any[]>([]);

    function onInput(event: Event) {
        input.value = (event.target as HTMLInputElement).value;
    }

    useEffect(
        (): void => {
            service(input.value).then((value) => (items.value = value))
        },
        [input.value]
    );

    /**
     * Lazy Loading with Function Data Provider
     * dataProvider={dataProvider}
     */
    return <ComboBox {...props} ref={ref} onInput={onInput} items={items.value}/>;
});

export default AutoComboBox;

