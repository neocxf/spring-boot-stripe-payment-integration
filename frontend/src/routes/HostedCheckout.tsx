import {Center, Heading, VStack} from "@chakra-ui/react";
import {useState} from "react";
import CartItem, {ItemData} from "../components/CartItem.tsx";
import TotalFooter from "../components/TotalFooter.tsx";
import CustomerDetails from "../components/CustomerDetails.tsx";
import {Products} from '../data.ts'

function HostedCheckout() {
    const [items] = useState<ItemData[]>(Products)
    return <>
        <Center h={'100vh'} color='black'>
            <VStack spacing='24px'>
                <Heading>Hosted Checkout Example</Heading>
                {items.map(elem => {
                    return <CartItem key={elem.id} data={elem} mode={'checkout'}/>
                })}
                <TotalFooter total={30} mode={"checkout"}/>
                <CustomerDetails data={items} orderId={"1754041736853237761"} endpoint={"/checkout/hosted"} mode={"checkout"}/>
            </VStack>
        </Center>
    </>
}

export default HostedCheckout