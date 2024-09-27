import React, {useEffect, useState} from "react";
import {UserEndpoint} from "Frontend/generated/endpoints";
import User from "Frontend/generated/ch/rupfizupfi/deck/data/User";
import UserModel from "Frontend/generated/ch/rupfizupfi/deck/data/UserModel";
import {ComboBox} from "@vaadin/react-components";
import {ComboBoxProps} from "@vaadin/react-components/ComboBox";

/**
 * ComboBox for selecting the owner User or null for all users.
 * @constructor
 */
export default function OwnerSelector(field: ComboBoxProps<User>): React.JSX.Element {
    const [values, setValues] = useState<(User | null)[]>([]);
    const emptyUser = UserModel.createEmptyValue();
    emptyUser.id = null;
    emptyUser.name = 'All Users';

    useEffect(() => {
        // Fetch all users
        UserEndpoint.list(undefined, undefined).then(users => setValues([emptyUser, ...(users || []).map(u => u!)]));
    }, []);

    return (
        <ComboBox
            {...field}
            itemIdPath="id"
            itemValuePath="id"
            itemLabelPath="name"
            items={values}
        />
    );
}