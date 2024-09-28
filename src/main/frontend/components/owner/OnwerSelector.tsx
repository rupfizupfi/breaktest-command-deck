import React, {useEffect, useState} from "react";
import {UserEndpoint} from "Frontend/generated/endpoints";
import User from "Frontend/generated/ch/rupfizupfi/deck/data/User";
import {ComboBox, ComboBoxElement, ComboBoxProps} from "@vaadin/react-components";
import Direction from "Frontend/generated/org/springframework/data/domain/Sort/Direction";

/**
 * ComboBox for selecting the owner User or null for all users.
 * @constructor
 */
const OwnerSelector = React.forwardRef<ComboBoxElement<any>, ComboBoxProps<any>>((props, ref) => {
    const [values, setValues] = useState<(User | null)[]>([]);

    useEffect(() => {
        UserEndpoint.list({pageNumber:0,pageSize:100,sort: {orders:[{property:'name', direction:Direction.ASC, ignoreCase:false }]}}, undefined).then(users => setValues([null, ...(users || []).map(u => u!)]));
    }, []);

    return (
        <ComboBox
            {...props}
            ref={ref}
            itemIdPath="id"
            itemValuePath="id"
            itemLabelPath="name"
            items={values}
        />
    );
});

export default OwnerSelector;