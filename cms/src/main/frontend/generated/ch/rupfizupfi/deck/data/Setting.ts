interface Setting<T = unknown> {
    key: string;
    value: T;
    type: string;
}
export default Setting;
