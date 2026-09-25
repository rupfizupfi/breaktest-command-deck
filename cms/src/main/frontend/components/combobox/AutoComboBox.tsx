import {ComboBox, ComboBoxElement, ComboBoxProps} from "@vaadin/react-components";
import React, {useEffect} from "react";
import {useSignal} from "@vaadin/hilla-react-signals";
import {AutoComboService} from "cms/components/combobox/service";

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

    // React 19 types onInput as InputEventHandler (a synthetic event), not a DOM Event.
    function onInput(event: React.InputEvent<ComboBoxElement<any>>) {
        input.value = (event.target as HTMLInputElement).value;
    }

    useEffect(
        (): (() => void) => {
            // Only the latest request may fill the list.
            let ignore = false;
            service(input.value).then((value) => {
                if (!ignore) {
                    items.value = value;
                }
            });
            return () => {
                ignore = true;
            };
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

