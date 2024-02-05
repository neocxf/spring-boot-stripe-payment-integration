import {ItemData} from "./components/CartItem.tsx";

export const Products: ItemData[] = [
    {
        description: "Premium Shoes",
        image: "https://source.unsplash.com/NUoPWImmjCU",
        name: "Puma Shoes",
        price: 20,
        quantity: 1,
        id: "shoe",
        // orderId: "1754041736853237761",
    },
    {
        description: "Comfortable everyday slippers",
        image: "https://source.unsplash.com/K_gIPI791Jo",
        name: "Nike Sliders",
        price: 10,
        quantity: 1,
        id: "slippers",
        // orderId: "1753788492667224066"
    },
]

export const Subscriptions: ItemData[] = Products