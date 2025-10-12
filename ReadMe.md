We made an intelligent Android application that uses the power of Large Language Models (LLMs) to provide contextual,
 AI-powered smart replies for your WhatsApp conversations.
 basically it exploits the whatsapp notification's pending intent feature , when someone send a message on whatsapp ,
 the notifation carries a pending intent feature and this interact with OS via notification , our  app read that pending intent , 
 get its text input , search  messages which are uploaded through .txt file of export chat , then gie a promt to ai and get the response,
 after getting response it generates a notification that fullfils the whatsapps pending intent and sent the message.
tech used : kotlin and gemini api.
future work : we can add multiple AI models , if one's limit is exausted then we can you other models , we can add the popup for the user to follow how to use the app
photo proffs and vidoes are added on the drive  link : https://drive.google.com/drive/folders/1YD9qIaI-b9-C68_rjdjeP04rN5lFGQMm?usp=sharing
user flow : import files -> parsing -> store with embedding from ai -> notification arrive ->read notification -> search for the message in the embedding (if found send +10 and -10 messages , also send 50 currennt messages , else send 100 current messages) -> send prompt -> get response ->generate notifaction -> fulfill pending intent to send the message.

