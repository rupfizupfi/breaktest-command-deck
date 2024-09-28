import React, {useEffect, useState} from "react";
import {UserEndpoint} from "Frontend/generated/endpoints";
import User from "Frontend/generated/ch/rupfizupfi/deck/data/User";
import {ComboBox, ComboBoxElement, ComboBoxProps} from "@vaadin/react-components";

/**
 * ComboBox for selecting the owner User or null for all users.
 * @constructor
 */
const OwnerSelector = React.forwardRef<ComboBoxElement<any>, ComboBoxProps<any>>((props, ref) => {
    const [values, setValues] = useState<(User | undefined)[]>([]);

    useEffect(() => {
        // @ts-ignore
        UserEndpoint.list().then(users => setValues([undefined, ...(users || []).map(u => u!)]));
    }, []);

    return (
        <ComboBox
            {...props}
            ref={ref}
            itemIdPath="id"
            itemValuePath="name"
            itemLabelPath="name"
            items={values}
        />
    );
});

export default OwnerSelector;