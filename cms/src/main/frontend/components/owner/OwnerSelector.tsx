import React, {useEffect, useState} from "react";
import {UserEndpoint} from "Frontend/generated/endpoints";
import User from "Frontend/generated/ch/rupfizupfi/deck/data/User";
import {ComboBox, ComboBoxElement, ComboBoxProps} from "@vaadin/react-components";
import Direction from "Frontend/generated/org/springframework/data/domain/Sort/Direction";

/** ComboBox for the owner User, or null for all users. */
const OwnerSelector = React.forwardRef<ComboBoxElement<any>, ComboBoxProps<any>>((props, ref) => {
    const [values, setValues] = useState<(User | null)[]>([]);

    useEffect(() => {
        let cancelled = false;
        UserEndpoint.list({pageNumber:0,pageSize:100,sort: {orders:[{property:'name', direction:Direction.ASC, ignoreCase:false }]}}, undefined)
            .then(
                users => [null, ...(users ?? []).filter((u): u is User => u !== undefined && u !== null)],
                // The rpc middleware toasts the cause; "all users" (null) stays selectable.
                () => [null])
            .then(next => {
                if (!cancelled) {
                    setValues(next);
                }
            });
        return () => {
            cancelled = true;
        };
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
